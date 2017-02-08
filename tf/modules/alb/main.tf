resource "aws_security_group" "alb_sg" {
  name        = "${var.environment}-${var.name}-alb-sg"
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

resource "aws_alb" "main" {
  name            = "${var.environment}-${var.name}-alb"
  internal        = false
  security_groups = ["${aws_security_group.alb_sg.id}"]
  subnets         = "${var.subnet_ids}"
  enable_deletion_protection = false
}

resource "aws_alb_target_group" "website" {
  name     = "${var.environment}-${var.name}-alb-tg"
  port     = "${var.forward_port}"
  protocol = "HTTP"
  vpc_id   = "${var.vpc_id}"
}

resource "aws_alb_listener" "front_end" {
  load_balancer_arn = "${aws_alb.main.id}"
  port              = "${var.listen_port}"
  protocol          = "${var.protocol}"
  ssl_policy        = "${var.ssl_policy}"
  certificate_arn   = "${var.certificate_arn}"

  default_action {
    target_group_arn = "${aws_alb_target_group.website.id}"
    type             = "forward"
  }
}
