from selenium import webdriver
from webdriver_manager.chrome import ChromeDriverManager
import random
import time
import string
import secrets
import os

driver = webdriver.Chrome(ChromeDriverManager().install())

alphabet = string.ascii_letters + string.digits
password = ''.join(secrets.choice(alphabet) for i in range(16))

driver.get('https://en.wikipedia.org/wiki/Special:Random')
temp = driver.find_element_by_class_name("firstHeading").text
for char in string.punctuation:
    temp = temp.replace(char, '') #REMOVES ALL PUNCTUATION
for char in string.digits:
    temp = temp.replace(char, '') #wtf
temp = "".join(filter(lambda char: char in string.printable, temp)) #this
name = ''.join(temp.split())
name = name[:random.randint(5,7)]


randomNumber = random.randint(10000,99999)
randomEmailNumber = random.randint(50,99)


dirname = os.path.dirname(__file__)
text_file_path = os.path.join(dirname, 'namesforreddit.txt')
text_file = open(text_file_path, "a")
text_file.write("USER: " + name + str(randomNumber) + " PWD: " + password)
text_file.write("\n")
text_file.close()

finalName = name+str(randomNumber)
time.sleep(1)

# yea this is the juicy part
driver.get('https://www.reddit.com/register/')
driver.find_element_by_id('regEmail').send_keys(f"temp{randomEmailNumber}@zajc.eu.org")
time.sleep(1)
driver.find_element_by_xpath ("//button[contains(text(),'Continue')]").click()
time.sleep(1)
driver.find_element_by_id('regUsername').send_keys(finalName)
driver.find_element_by_id('regPassword').send_keys(password)
time.sleep(14)
driver.find_element_by_xpath ("//button[contains(text(),'Sign Up')]").click()
time.sleep(2)
