#!/bin/bash -ex

exec > >(tee /var/log/user-data.log|logger -t user-data -s 2>/dev/console) 2>&1

echo "Downloading uberjar from s3://${releases_bucket}/${release_artifact}"
mkdir /releases

aws s3 cp s3://${releases_bucket}/${release_artifact} /releases

echo "Launching..."
${launch_command}
