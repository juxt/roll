#!/bin/bash -ex

exec > >(tee /var/log/user-data.log|logger -t user-data -s 2>/dev/console) 2>&1

apt-get update
apt install -y awscli
apt-get install -y openjdk-8-jdk

echo "Downloading release artifact from s3://{{releases-bucket}}/{{release-artifact}}"
mkdir /releases

aws s3 cp s3://{{releases-bucket}}/{{release-artifact}} /releases

cd /releases
echo "Launching..."
{{launch-command}}
