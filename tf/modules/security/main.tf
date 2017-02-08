resource "aws_security_group" "app" {
  name        = "${var.environment}_app"
  description = "Allow access to ${var.environment} application"

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = "${var.port}"
    to_port     = "${var.port}"
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 65535
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags {
    Name = "${var.environment}"
  }
}

resource "aws_iam_role" "application_host" {
  name = "${var.environment}-role"
  assume_role_policy = <<EOF
{
  "Statement": [
    {
      "Action": "sts:AssumeRole",
      "Effect": "Allow",
      "Principal": {
        "Service": "ec2.amazonaws.com"
      },
      "Sid": ""
    }
  ],
  "Version": "2012-10-17"
}
EOF
}

resource "aws_iam_instance_profile" "application_host" {
  name = "${var.environment}"
  roles = ["${aws_iam_role.application_host.name}"]
}
