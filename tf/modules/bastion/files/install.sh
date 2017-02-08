#!/bin/bash -ex
exec > >(tee /var/log/user-data.log|logger -t user-data -s 2>/dev/console) 2>&1

# Install Java
wget --no-cookies --no-check-certificate --header "Cookie: gpw_e24=http%3A%2F%2Fwww.oracle.com%2F; oraclelicense=accept-securebackup-cookie" "https://download.oracle.com/otn-pub/java/jdk/8u51-b16/jdk-8u51-linux-x64.rpm"
rpm -ivh jdk-8u51-linux-x64.rpm

sudo yum update -y
sudo yum install nodejs npm --enablerepo=epel -y
sudo npm install npm -g -y
sudo yum install git-all -y

sudo npm install -g @juxt/mach lumo-cljs
