import urllib.request
import os

print("Downloading Maven Wrapper...")

files_to_download = {
    "mvnw.cmd": "https://raw.githubusercontent.com/apache/maven-wrapper/master/mvnw.cmd",
    "mvnw": "https://raw.githubusercontent.com/apache/maven-wrapper/master/mvnw",
    ".mvn/wrapper/maven-wrapper.properties": "https://raw.githubusercontent.com/apache/maven-wrapper/master/.mvn/wrapper/maven-wrapper.properties"
}

os.makedirs(".mvn/wrapper", exist_ok=True)

for path, url in files_to_download.items():
    print(f"Downloading {path}...")
    urllib.request.urlretrieve(url, path)
    
print("Done! Maven Wrapper installed successfully.")
