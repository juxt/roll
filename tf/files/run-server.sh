#!/bin/bash -ex

exec > >(tee /var/log/user-data.log|logger -t user-data -s 2>/dev/console) 2>&1

echo "Downloading release artefact from s3://{{releases-bucket}}/{{release-artifact}}"
mkdir /releases

aws s3 cp s3://{{releases-bucket}}/{{release-artifact}} /releases

echo "Launching..."
{{launch-command}}
