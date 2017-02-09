resource "aws_security_group" "sg" {
  name        = "${var.environment}-bastion-sg"
  description = "Allow access to ${var.environment}-bastion application"

  ingress {
    from_port   = 22
    to_port     = 22
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
    Name = "${var.environment}-bastion"
  }
}

resource "aws_iam_role" "bastion_host" {
  name = "${var.environment}-bastion-role"
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

resource "aws_iam_instance_profile" "bastion_host" {
  name = "${var.environment}-bastion"
  roles = ["${aws_iam_role.bastion_host.name}"]
}

resource "aws_instance" "bastion" {
  ami                         = "${var.ami}"
  instance_type               = "${var.instance_type}"
  iam_instance_profile        = "${aws_iam_instance_profile.bastion_host.name}"
  availability_zone           = "${var.availability_zone}"
  vpc_security_group_ids      = ["${aws_security_group.sg.id}"]
  associate_public_ip_address = true
  key_name                    = "${var.key_name}"
  user_data                   = "${var.user_data}"

  tags {
    Name = "${var.environment}-bastion"
    Type = "Bastion"
  }
}
