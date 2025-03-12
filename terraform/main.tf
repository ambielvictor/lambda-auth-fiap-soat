terraform {
  backend "s3" {
    bucket         = "terraform-state-fiap-soat-auth"
    key            = "infra/terraform.tfstate"
    region         = "us-east-1"
    encrypt        = true
  }
}

provider "aws" {
  region = "us-east-1"
}

# Nome do bucket onde o .jar será armazenado
variable "s3_bucket_builds" {
  default = "lambda-build-fiap-soat-auth"
}

# Nome do arquivo .jar existente
variable "local_jar_path" {
  default = "../lambda/target/lambda-1.0-SNAPSHOT.jar"
}

# Gerar um nome único para cada upload
resource "time_static" "build_id" {}

# Fazer upload do .jar existente para o S3
resource "aws_s3_object" "lambda_jar" {
  bucket = var.s3_bucket_builds
  key    = "app-${time_static.build_id.unix}.jar"
  source = var.local_jar_path
  etag = fileexists(var.local_jar_path) ? filemd5(var.local_jar_path) : null
}


# Criar uma Role para a Lambda
resource "aws_iam_role" "lambda_role" {
  name = "lambda-api-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [{
      Effect = "Allow",
      Principal = {
        Service = "lambda.amazonaws.com"
      },
      Action = "sts:AssumeRole"
    }]
  })
}

# Permissões para a Lambda acessar S3, DynamoDB e CloudWatch Logs
resource "aws_iam_policy" "lambda_policy" {
  name        = "lambda-policy"
  description = "Permite que a Lambda acesse S3, DynamoDB e logs"

  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect = "Allow",
        Action = [
          "dynamodb:GetItem",
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents",
          "s3:GetObject",
          "s3:PutObject"
        ],
        Resource = "*"
      }
    ]
  })
}

# Anexar a política à Role da Lambda
resource "aws_iam_role_policy_attachment" "attach_lambda_policy" {
  policy_arn = aws_iam_policy.lambda_policy.arn
  role       = aws_iam_role.lambda_role.name
}

# Criar a Lambda apontando para o .jar no S3
resource "aws_lambda_function" "auth_lambda" {
  function_name    = "auth-lambda"
  handler          = "com.fiap.challenge.food.lambda.AuthWithDynamoDB::handleRequest"
  runtime          = "java21"
  role             = aws_iam_role.lambda_role.arn
  s3_bucket        = var.s3_bucket_builds
  s3_key           = aws_s3_object.lambda_jar.key
  timeout          = 30

  environment {
    variables = {
      TABLE_NAME = "fast-food-consumer"
    }
  }
}

# Buscar API Gateway existente
data "aws_apigatewayv2_api" "existing_api" {
  api_id = "vqxay3q1i1"
}

# Criar integração com a Lambda no API Gateway existente
resource "aws_apigatewayv2_integration" "lambda_integration" {
  api_id                 = data.aws_apigatewayv2_api.existing_api.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.auth_lambda.invoke_arn
  payload_format_version = "2.0"
}

# Criar rota POST /login no API Gateway existente
resource "aws_apigatewayv2_route" "login_route" {
  api_id    = data.aws_apigatewayv2_api.existing_api.id
  route_key = "POST /login"
  target    = "integrations/${aws_apigatewayv2_integration.lambda_integration.id}"
}

# Permitir que a API Gateway chame a Lambda
resource "aws_lambda_permission" "apigateway_lambda" {
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.auth_lambda.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${data.aws_apigatewayv2_api.existing_api.execution_arn}/*/*"
}

# Output da URL da API Gateway existente
output "api_url" {
  value = "${data.aws_apigatewayv2_api.existing_api.api_endpoint}/login"
}
