resource "aws_security_group" "alb_sg" {
  name        = "${var.environment}-${var.name}-${var.listen_port}-alb-sg"
  description = "controls access to the application load balancer"

  ingress {
    protocol    = "tcp"
    from_port   = "${var.listen_port}"
    to_port     = "${var.listen_port}"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port = 0
    to_port   = 0
    protocol  = "-1"

    cidr_blocks = [
      "0.0.0.0/0",
    ]
  }
}
