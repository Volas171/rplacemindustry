package placed;

import static java.awt.image.BufferedImage.TYPE_INT_RGB;
import static java.lang.Integer.toHexString;
import static java.lang.Long.MAX_VALUE;
import static java.lang.String.format;
import static java.lang.System.*;
import static java.lang.Thread.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.stream;
import static java.util.Collections.shuffle;
import static java.util.concurrent.Executors.*;
import static java.util.concurrent.TimeUnit.*;
import static java.util.function.Predicate.not;
import static org.apache.commons.lang3.time.DurationFormatUtils.formatDurationWords;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import javax.annotation.*;
import javax.imageio.ImageIO;

import com.google.gson.JsonParser;

import kong.unirest.Unirest;

public class Placed {

	private static final String TARGETS_PATH = "/home/marko/projects/rplace/targets/";
	private static final String USERS_PATH = "/home/marko/projects/rplace/users.txt";

	private static final int UPDATE_INTERVAL = 8;
	private static final int REPORT_INTERVAL = 20;

	private static final int CANVAS_COUNT = 3;

	private static final String USER_AGENT =
		"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/98.0.4758.102 Safari/537.36";

	private static final BufferedImage[] CANVASES = new BufferedImage[CANVAS_COUNT];
	private static final BufferedImage[] TARGETS = new BufferedImage[CANVAS_COUNT];
	static {
		try {
			for (int i = 0; i < CANVAS_COUNT; i++)
				TARGETS[i] = ImageIO.read(new File(TARGETS_PATH + i + ".png"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static final ScheduledExecutorService MAIN = newSingleThreadScheduledExecutor();
	private static final ScheduledExecutorService SIDE = newSingleThreadScheduledExecutor();
	private static final ScheduledExecutorService LESS = newSingleThreadScheduledExecutor();
	private static final ExecutorService UPDATES = newFixedThreadPool(100);
	private static final ExecutorService[] CANVAS_UPDATE = new ExecutorService[CANVAS_COUNT];
	static {
		for (int i = 0; i < CANVAS_UPDATE.length; i++)
			CANVAS_UPDATE[i] = newSingleThreadExecutor();
	}
	private static final WebSocket[] WS = new WebSocket[CANVAS_COUNT];
	private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

	public static void main(String[] argv) throws Exception {
		verifyCanvas();
		var accounts = createAccounts();
		regenerateWebSockets(accounts);

		// We check for changes every N seconds and correct them accordingly
		MAIN.scheduleWithFixedDelay(() -> updateAll(accounts), UPDATE_INTERVAL, UPDATE_INTERVAL, SECONDS);

		// We need to regenerate our tokens every so often (not sure how often so I'm setting
		// this to 20 minutes
		SIDE.scheduleWithFixedDelay(() -> regenerateTokens(accounts), 20, 20, MINUTES);

		// Because we might not be able to catch up with all the deltas, I opted to reset the
		// WebSocket connection entirely every 3 minutes (giving us a full canvas every time)
		SIDE.scheduleWithFixedDelay(() -> regenerateWebSockets(accounts), 3, 3, MINUTES);

		// Less important things
		LESS.scheduleWithFixedDelay(() -> report(accounts), REPORT_INTERVAL, REPORT_INTERVAL, SECONDS);

		while (true)
			sleep(MAX_VALUE);
	}

	@Nonnull
	private static List<Account> createAccounts() throws IOException {
		out.println("[ACCOUNTS] INFO Parsing credentials");
		var credentials = Files.lines(Paths.get(USERS_PATH))
			.filter(not(String::isEmpty))
			.map(a -> a.split("\\|"))
			.toArray(i -> new String[i][]);
		out.println("[ACCOUNTS] INFO Logging into accounts");
		@SuppressWarnings("null")
		var accounts = new ArrayList<>(Arrays.stream(credentials)
			.parallel()
			.map(c -> new Account(c[0], c[1]))
			.filter(Objects::nonNull)
			.toList());
		out.printf("[ACCOUNTS] INFO Done! Logged into %d accounts%n", accounts.size());
		shuffle(accounts);
		return accounts;
	}

	private static void verifyCanvas() {
		out.println("[  MAIN  ] INFO Checking the target canvas");
		for (int i = 0; i < CANVAS_COUNT; i++) {
			var target = TARGETS[i];
			for (int y = 0; y < DIMENSIONS; y++) {
				for (int x = 0; x < DIMENSIONS; x++) {
					int rgb = target.getRGB(x, y);
					if ((rgb & 0xff000000) != 0 && Color.getColor(rgb) == null) {
						out.printf("[ PARSER ] WARN Unknown color at (%d, %d, %d): %s%n", x, y, i, toHexString(rgb));
					}
				}
			}
		}
	}

	private static void regenerateTokens(@Nonnull List<Account> accounts) {
		out.println("[ACCOUNTS] INFO Regenerating account tokens");
		accounts.parallelStream().forEach(Account::regenerateToken);
		shuffle(accounts);
	}

	private static void regenerateWebSockets(@Nonnull List<Account> accounts) {
		out.println("[WEBSOCK ] INFO Regenerating the WebSockets");
		var candidates =
			accounts.stream().filter(not(Account::isDisabled)).limit(WS.length).toArray(i -> new Account[i]);
		for (int i = 0; i < WS.length; i++) {
			try {
				if (WS[i] != null)
					WS[i].sendClose(1000, "quit");
				WS[i] = openWebSocket(candidates[i], i);
			} catch (InterruptedException | ExecutionException | URISyntaxException e) { // NOSONAR no
				out.printf("[WEBSOCK ] ERRO Couldn't regenerate WebSocket %d%n", i);
				WS[i] = null;
			}
		}
	}

	private static WebSocket openWebSocket(Account account, int i) throws InterruptedException, ExecutionException,
																   URISyntaxException {
		return HTTP_CLIENT.newWebSocketBuilder()
			.header("User-Agent", USER_AGENT)
			.header("Accept", "*/*")
			.header("Origin", "https://hot-potato.reddit.com")
			.buildAsync(new URI(WS_ENDPOINT), new WebSocket.Listener() {

				@Override
				public void onOpen(WebSocket ws) {
					ws.sendText(format(AUTH_JSON, account.getToken()), true);
					ws.request(1);
					out.printf("[WEBSOCK ] (C%d) Connected to the WebSocket with %s!%n", i, account.getUsername());
				}

				@Override
				@SuppressWarnings("null")
				public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
					var json = JsonParser.parseString(text.toString()).getAsJsonObject();
					switch (json.get("type").getAsString()) {
						case "connection_ack" -> ws.sendText(format(START_JSON, i, i), true);
						case "data" -> {
							var data = json.getAsJsonObject("payload")
								.getAsJsonObject("data")
								.getAsJsonObject("subscribe")
								.getAsJsonObject("data");
							var url = data.get("name").getAsString();
							int id = Integer.parseInt(json.get("id").getAsString());
							switch (data.get("__typename").getAsString()) {
								case "FullFrameMessageData" -> {
									CANVASES[id] = fetchImage(url);
									CANVAS_UPDATE[id].shutdownNow();
									CANVAS_UPDATE[id] = newSingleThreadExecutor();
								}
								case "DiffFrameMessageData" -> CANVAS_UPDATE[id].submit(() -> {
									var delta = fetchImage(url);
									if (CANVASES[id] != null) {
										for (int y = 0; y < DIMENSIONS; y++) {
											for (int x = 0; x < DIMENSIONS; x++) {
												int rgb = delta.getRGB(x, y);
												if (rgb != 0)
													CANVASES[id].setRGB(x, y, rgb);
											}
										}
									}
								});
								default -> out.println(data);
							}
						}
						case "ka" -> { /* i have no clue what this does but it doesn't seem important */ }
						case "connection_error" -> {
							out.println("[WEBSOCK ] ERRO Couldn't connect to the websocket, please get a new token");
							exit(1);
						}
						default -> out.println("[WEBSOCK ] WARN Got an unknown payload: " + json);
					}
					ws.request(1);
					return null;
				}

				@Override
				public void onError(WebSocket webSocket, Throwable error) {
					error.printStackTrace();
				}

			})
			.get();
	}

	static void updateAll(@Nonnull List<Account> accounts) {
		var coordinates = findCoordinates(accounts);

		if (coordinates != null && !coordinates.isEmpty())
			updateCoordinates(accounts, coordinates);
	}

	private static void updateCoordinates(@Nonnull List<Account> accounts, @Nonnull Queue<Coordinate> coordinates) {
		out.printf("[UPDATER ] INFO Updating %d coordinates%n", coordinates.size());
		for (int i = 0; i < accounts.size() && !coordinates.isEmpty(); i++) {
			var account = accounts.get(i);
			if (account.canUse()) {
				var coordinate = coordinates.remove();
				UPDATES.submit(() -> {
					@SuppressWarnings("null")
					long timeout = update(account, coordinate);
					if (timeout == -1) {
						out.printf("[ACCOUNTS] WARN Disabled %s%n", account.getUsername());
						account.disable();

					} else if (timeout == -2) {
						out.printf("[ACCOUNTS] %s was banned%n", account.getUsername());
						account.disable();

					} else {
						timeout += getRatelimitRandomDelta();
						out.printf("[ACCOUNTS] INFO Timing %s out for %s%n", account.getUsername(),
								   formatDurationWords(timeout, true, true));
						account.setTimeout(timeout);
					}
				});
			}
		}
	}

	@Nullable
	private static Queue<Coordinate> findCoordinates(List<Account> accounts) {
		int quantity = (int) accounts.stream().filter(Account::canUse).count();
		if (quantity == 0)
			return null;

		var coordinates = new ArrayList<Coordinate>(100);
		for (int i = 0; i < CANVAS_COUNT; i++) {
			if (WS[i] == null || WS[i].isInputClosed())
				continue;
			for (int y = 0; y < DIMENSIONS; y++) {
				for (int x = 0; x < DIMENSIONS; x++) {
					int rgb = TARGETS[i].getRGB(x, y);
					if ((rgb & 0xff000000) != 0 && rgb != CANVASES[i].getRGB(x, y))
						coordinates.add(new Coordinate(Color.getColor(rgb), x, y, i));
				}
			}
		}
		shuffle(coordinates);
		var queue = new ArrayBlockingQueue<Coordinate>(quantity);
		coordinates.stream().limit(quantity).forEach(queue::add);
		return queue;
	}

	@SuppressWarnings("null")
	private static long update(@Nonnull Account account, @Nonnull Coordinate coordinate) {
		synchronized (account) { // NOSONAR i know what i'm doing
			try {
				out.printf("[UPDATER ] (%s) INFO Updating (%d, %d, %d) to %s%n", account.getUsername(), coordinate.x(),
						   coordinate.y(), coordinate.canvas(), coordinate.color().toString());
				var resp =
					updateRequest(coordinate.x(), coordinate.y(), coordinate.canvas(), coordinate.color(), account);
				if (resp.statusCode() == 200) {
					long timeout = getTimeout(resp.body());
					if (timeout == 2147483647000L)
						return -2;
					return timeout - currentTimeMillis();
				} else {
					throw new IllegalStateException(format("Bad response code: %d%n%s", resp.statusCode(),
														   resp.body()));
				}
			} catch (InterruptedException e) {
				currentThread().interrupt();
			} catch (Exception e) {
				e.printStackTrace(out);
			}
			return -1;
		}
	}

	private static long getTimeout(@Nonnull String json) {
		var root = JsonParser.parseString(json).getAsJsonObject();
		var data = root.get("data");
		if (data.isJsonNull()) {
			var ratelimit = root.getAsJsonArray("errors")
				.get(0)
				.getAsJsonObject()
				.getAsJsonObject("extensions")
				.get("nextAvailablePixelTs");
			if (ratelimit.isJsonNull())
				return MINUTES.toMillis(5);
			else
				return ratelimit.getAsLong();
		} else
			return data.getAsJsonObject()
				.getAsJsonObject("act")
				.getAsJsonArray("data")
				.get(0)
				.getAsJsonObject()
				.getAsJsonObject("data")
				.get("nextAvailablePixelTimestamp")
				.getAsLong();
	}

	@SuppressWarnings("null")
	private static java.net.http.HttpResponse<String> updateRequest(int x, int y, int canvas, Color target,
																	@Nonnull Account account) throws URISyntaxException,
																							  IOException,
																							  InterruptedException {
		return gqlRequest(format(UPDATE_JSON, x, y, target.getIndex(), canvas), account.getToken());
	}

	static void report(@Nonnull List<Account> accounts) {
		int goodPixels = 0;
		int contestedPixels = 0;
		for (int i = 0; i < TARGETS.length; i++) {
			for (int y = 0; y < DIMENSIONS; y++) {
				for (int x = 0; x < DIMENSIONS; x++) {
					int rgb = TARGETS[i].getRGB(x, y);
					if ((rgb & 0xff000000) != 0) {
						if (rgb == CANVASES[i].getRGB(x, y))
							goodPixels++;
						else {
							contestedPixels++;
						}
					}
				}
			}
		}
		int disabledAccounts = (int) accounts.stream().filter(Account::isDisabled).count();
		int usableAccounts = (int) accounts.stream().filter(Account::canUse).count();
		out.printf("""
			[ STATUS ] INFO Pixels:   %04d good   %04d contested
			[ STATUS ] INFO Accounts: %04d usable %04d disabled  %04d total%n""", goodPixels, contestedPixels,
				   usableAccounts, disabledAccounts, accounts.size());
	}

	@Nullable
	public static String login(@Nonnull String username, @Nonnull String password) {
		Unirest.config().cookieSpec("standard");
		var resp = Unirest.post(LOGIN_ENDPOINT + username)
			.accept("application/json, text/javascript, */*; q=0.01")
			.contentType("application/x-www-form-urlencoded; charset=UTF-8")
			.header("Authority", "old.reddit.com")
			.header("X-Requested-With", "XMLHttpRequest")
			.header("User-Agent", USER_AGENT)
			.header("Referer", "https://old.reddit.com/")
			.header("Accept-Language", "en-US,en;q=0.9")
			.body(format(LOGIN_BODY, URLEncoder.encode(username, UTF_8), URLEncoder.encode(password, UTF_8)))
			.asJson();
		var cookies = resp.getCookies();
		var root = resp.getBody().getObject().getJSONObject("json");
		if (!root.has("data"))
			return null;

		var tokenv2 = Unirest.get("https://old.reddit.com/chat/minimize")
			.cookie(cookies)
			.cookie("reddit_session", URLEncoder.encode(root.getJSONObject("data").getString("cookie"), UTF_8))
			.cookie("edgebucket", "mMmdHtW5Hk0Ir6cSCL")
			.cookie("pc", "rg")
			.cookie("csv", "2")
			.accept("text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9")
			.header("Authority", "old.reddit.com")
			.header("User-Agent", USER_AGENT)
			.header("Referer", "https://old.reddit.com/")
			.header("Accept-Language", "en-US,en;q=0.9")
			.asEmpty()
			.getCookies()
			.getNamed("token_v2")
			.getValue();
		return JsonParser.parseString(new String(Base64.getDecoder().decode(tokenv2.split("\\.")[1]), UTF_8))
			.getAsJsonObject()
			.get("sub")
			.getAsString();
	}

	private static int getRatelimitRandomDelta() {
		// return ThreadLocalRandom.current().nextInt(2000, 180000);
		return 0;
	}

	public static record Coordinate(@Nonnull Color color, int x, int y, int canvas) {}

	public static class Account {

		@Nonnull
		private final String username;
		@Nonnull
		private final String password;
		private String token;
		private long timeout;
		private boolean disabled;

		public Account(@Nonnull String username, @Nonnull String password) {
			this.username = username;
			this.password = password;
			if (this.regenerateToken()) {
				try {
					this.timeout = getRatelimit(getToken()) + getRatelimitRandomDelta();
				} catch (InterruptedException e) {
					currentThread().interrupt();
					disable();

				} catch (Exception e) {
					out.printf("[ACCOUNTS] WARN Could not log into %s, disabling it%n", username);
					e.printStackTrace(out);
					disable();
				}
			}
		}

		@Nonnull
		@SuppressWarnings("null")
		public String getToken() {
			return this.token;
		}

		public boolean regenerateToken() {
			String newToken = null;
			try {
				newToken = login(this.username, this.password);
			} catch (Exception e) {
				e.printStackTrace(out);
			}
			if (newToken == null) {
				out.printf("[ACCOUNTS] WARN Couldn't log into %s%n", this.username);
				disable();
				return false;
			} else {
				this.token = newToken;
				return true;
			}
		}

		public boolean canUse() {
			return this.timeout < currentTimeMillis() && !isDisabled();
		}

		public boolean isDisabled() {
			return this.disabled;
		}

		public void setTimeout(long duration) {
			this.timeout = duration + currentTimeMillis();
		}

		public void disable() {
			this.disabled = true;
		}

		@Nonnull
		public String getUsername() {
			return this.username;
		}

		private static long getRatelimit(@Nonnull String token) throws URISyntaxException, IOException,
																InterruptedException {
			var resp = gqlRequest(RATELIMIT_JSON, token);
			if (resp.statusCode() != 200)
				throw new IllegalStateException(format("Bad response code: %d%n%s", resp.statusCode(), resp.body()));
			var ratelimit = JsonParser.parseString(resp.body())
				.getAsJsonObject()
				.getAsJsonObject("data")
				.getAsJsonObject("act")
				.getAsJsonArray("data")
				.get(0)
				.getAsJsonObject()
				.getAsJsonObject("data")
				.get("nextAvailablePixelTimestamp");
			if (ratelimit.isJsonNull())
				return System.currentTimeMillis();
			else
				return ratelimit.getAsLong();
		}

	}

	@Nonnull
	@SuppressWarnings("null")
	private static BufferedImage fetchImage(@Nonnull String url) {
		try {
			return ImageIO.read(new URL(url));
		} catch (IOException e) {
			e.printStackTrace();
			return new BufferedImage(1000, 1000, TYPE_INT_RGB);
		}
	}

	@Nonnull
	@SuppressWarnings("null")
	private static java.net.http.HttpResponse<String> gqlRequest(@Nonnull String query,
																 @Nonnull String token) throws URISyntaxException,
																						IOException,
																						InterruptedException {
		var r = HttpRequest.newBuilder(new URI(GQL_ENDPOINT))
			.POST(BodyPublishers.ofString(query))
			.setHeader("User-Agent", USER_AGENT)
			.setHeader("Accept", "*/*")
			.setHeader("Accept-Language", "en-US,en;q=0.5")
			.setHeader("Accept-Encoding", "gzip, deflate, br")
			.setHeader("Referer", "https://hot-potato.reddit.com/")
			.setHeader("content-type", "application/json")
			.setHeader("authorization", "Bearer " + token)
			.setHeader("apollographql-client-name", "mona-lisa")
			.setHeader("apollographql-client-version", "0.0.1")
			.setHeader("Origin", "https://hot-potato.reddit.com")
			.setHeader("DNT", "1")
			.setHeader("Pragma", "no-cache")
			.setHeader("Cache-Control", "no-cache")
			.setHeader("TE", "trailers");
		return HTTP_CLIENT.send(r.build(), BodyHandlers.ofString());
	}

	///////////////////////////////////////
	// things we don't really care about //
	///////////////////////////////////////

	private enum Color {

		DARKEST_RED(0xFF6D001A, 0),
		DARK_RED(0xFFBE0039, 1),
		RED(0xFFFF4500, 2),
		ORANGE(0xFFFFA800, 3),
		YELLOW(0xFFFFD635, 4),
		PALE_YELLOW(0xFFFFF8B8, 5),
		DARK_GREEN(0xFF00A368, 6),
		GREEN(0xFF00CC78, 7),
		LIGHT_GREEN(0xFF7EED56, 8),
		DARK_TEAL(0xFF00756F, 9),
		TEAL(0xFF009EAA, 10),
		LIGHT_TEAL(0xFF00CC00, 11),
		DARK_BLUE(0xFF2450A4, 12),
		BLUE(0xFF3690EA, 13),
		LIGHT_BLUE(0xFF51E9F4, 14),
		INDIGO(0xFF493AC1, 15),
		PERIWINKLE(0xFF6A5CFF, 16),
		LAVENDER(0xFF94B3FF, 17),
		DARK_PURPLE(0xFF811E9F, 18),
		PURPLE(0xFFB44AC0, 19),
		PALE_PURPLE(0xFFE4ABFF, 20),
		MAGENTA(0xFFDE107F, 21),
		PINK(0xFFFF3881, 22),
		LIGHT_PINK(0xFFFF99AA, 23),
		DARK_BROWN(0xFF6D482F, 24),
		BROWN(0xFF9C6926, 25),
		BEIGE(0xFFFFB470, 26),
		BLACK(0xFF000000, 27),
		DARK_GRAY(0xFF515252, 28),
		GRAY(0xFF898D90, 29),
		LIGHT_GRAY(0xFFD4D7D9, 30),
		WHITE(0xFFFFFFFF, 31);

		private final int rgb;
		private final int index;

		private Color(int color, int index) {
			this.rgb = color;
			this.index = index;
		}

		public int getRgb() {
			return this.rgb;
		}

		public int getIndex() {
			return this.index;
		}

		public static Color getColor(int rgb) {
			return stream(Color.values()).filter(c -> c.getRgb() == rgb).findAny().orElse(null);
		}

	}

	private static final int DIMENSIONS = 1000;

	private static final String LOGIN_ENDPOINT = "https://old.reddit.com/api/login/";
	private static final String LOGIN_BODY = "op=login-main&api_type=json&user=%s&passwd=%s";
	private static final String GQL_ENDPOINT = "https://gql-realtime-2.reddit.com/query";
	private static final String WS_ENDPOINT = "wss://gql-realtime-2.reddit.com/query";
	private static final String START_JSON =
		"{\"id\":\"%d\",\"type\":\"start\",\"payload\":{\"variables\":{\"input\":{\"channel\":{\"teamOwner\":\"AFD2022\",\"category\":\"CANVAS\",\"tag\":\"%d\"}}},\"extensions\":{},\"operationName\":\"replace\",\"query\":\"subscription replace($input: SubscribeInput!) {\\n  subscribe(input: $input) {\\n    id\\n    ... on BasicMessage {\\n      data {\\n        __typename\\n        ... on FullFrameMessageData {\\n          __typename\\n          name\\n          timestamp\\n        }\\n        ... on DiffFrameMessageData {\\n          __typename\\n          name\\n          currentTimestamp\\n          previousTimestamp\\n        }\\n      }\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"}}";
	private static final String UPDATE_JSON =
		"{\"operationName\":\"setPixel\",\"variables\":{\"input\":{\"actionName\":\"r/replace:set_pixel\",\"PixelMessageData\":{\"coordinate\":{\"x\":%d,\"y\":%d},\"colorIndex\":%d,\"canvasIndex\":%d}}},\"query\":\"mutation setPixel($input: ActInput!) {\\n  act(input: $input) {\\n    data {\\n      ... on BasicMessage {\\n        id\\n        data {\\n          ... on GetUserCooldownResponseMessageData {\\n            nextAvailablePixelTimestamp\\n            __typename\\n          }\\n          ... on SetPixelResponseMessageData {\\n            timestamp\\n            __typename\\n          }\\n          __typename\\n        }\\n        __typename\\n      }\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"}";
	private static final String RATELIMIT_JSON =
		"{\"query\":\"mutation GetPersonalizedTimer{\\n  act(\\n    input: {actionName: \\\"r/replace:get_user_cooldown\\\"}\\n  ) {\\n    data {\\n      ... on BasicMessage {\\n        id\\n        data {\\n          ... on GetUserCooldownResponseMessageData {\\n            nextAvailablePixelTimestamp\\n          }\\n        }\\n      }\\n    }\\n  }\\n}\\n\\n\\nsubscription SUBSCRIBE_TO_CONFIG_UPDATE {\\n  subscribe(input: {channel: {teamOwner: AFD2022, category: CONFIG}}) {\\n    id\\n    ... on BasicMessage {\\n      data {\\n        ... on ConfigurationMessageData {\\n          __typename\\n          colorPalette {\\n            colors {\\n              hex\\n              index\\n            }\\n          }\\n          canvasConfigurations {\\n            dx\\n            dy\\n            index\\n          }\\n          canvasWidth\\n          canvasHeight\\n        }\\n      }\\n    }\\n  }\\n}\\n\\n\\nsubscription SUBSCRIBE_TO_CANVAS_UPDATE {\\n  subscribe(\\n    input: {channel: {teamOwner: AFD2022, category: CANVAS, tag: \\\"0\\\"}}\\n  ) {\\n    id\\n    ... on BasicMessage {\\n      id\\n      data {\\n        __typename\\n        ... on DiffFrameMessageData {\\n          currentTimestamp\\n          previousTimestamp\\n          name\\n        }\\n        ... on FullFrameMessageData {\\n          __typename\\n          name\\n          timestamp\\n        }\\n      }\\n    }\\n  }\\n}\\n\\n\\n\\n\\nmutation SET_PIXEL {\\n  act(\\n    input: {actionName: \\\"r/replace:set_pixel\\\", PixelMessageData: {coordinate: { x: 53, y: 35}, colorIndex: 3, canvasIndex: 0}}\\n  ) {\\n    data {\\n      ... on BasicMessage {\\n        id\\n        data {\\n          ... on SetPixelResponseMessageData {\\n            timestamp\\n          }\\n        }\\n      }\\n    }\\n  }\\n}\\n\\n\\n\\n\\n# subscription configuration($input: SubscribeInput!) {\\n#     subscribe(input: $input) {\\n#       id\\n#       ... on BasicMessage {\\n#         data {\\n#           __typename\\n#           ... on RReplaceConfigurationMessageData {\\n#             colorPalette {\\n#               colors {\\n#                 hex\\n#                 index\\n#               }\\n#             }\\n#             canvasConfigurations {\\n#               index\\n#               dx\\n#               dy\\n#             }\\n#             canvasWidth\\n#             canvasHeight\\n#           }\\n#         }\\n#       }\\n#     }\\n#   }\\n\\n# subscription replace($input: SubscribeInput!) {\\n#   subscribe(input: $input) {\\n#     id\\n#     ... on BasicMessage {\\n#       data {\\n#         __typename\\n#         ... on RReplaceFullFrameMessageData {\\n#           name\\n#           timestamp\\n#         }\\n#         ... on RReplaceDiffFrameMessageData {\\n#           name\\n#           currentTimestamp\\n#           previousTimestamp\\n#         }\\n#       }\\n#     }\\n#   }\\n# }\\n\",\"variables\":{\"input\":{\"channel\":{\"teamOwner\":\"GROWTH\",\"category\":\"R_REPLACE\",\"tag\":\"canvas:0:frames\"}}},\"operationName\":\"GetPersonalizedTimer\",\"id\":null}";
	private static final String AUTH_JSON = """
		{
			"type": "connection_init",
			"payload": {
				"Authorization": "Bearer %s"
			}
		}""";

}
