package com.fiap.challenge.food.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;

public class AuthWithDynamoDB implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String SECRET_KEY = "c66b463c-376f-452b-8a7e-b17491518828";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String TABLE_NAME = "fast-food-consumer";

    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        context.getLogger().log("Recebendo requisição de login...\n");

        try {
            String body = event.getBody();
            if (body == null || body.isEmpty()) {
                return generateResponse(400, "CPF não informado");
            }

            Map<String, String> requestData = objectMapper.readValue(body, Map.class);
            if (!requestData.containsKey("cpf")) {
                return generateResponse(400, "CPF não informado");
            }

            String cpf = requestData.get("cpf");
            if (!cpf.matches("\\d{11}")) {
                return generateResponse(401, "CPF inválido");
            }

            String consumerId = buscarConsumerIdNoDynamoDB(cpf);
            if (consumerId == null) {
                return generateResponse(404, "CPF não encontrado no banco de dados");
            }

            String token = gerarJWT(cpf, consumerId);
            Map<String, String> responseBody = new HashMap<>();
            responseBody.put("token", String.format("Bearer %s", token));

            return generateResponse(200, objectMapper.writeValueAsString(responseBody));

        } catch (Exception e) {
            context.getLogger().log("Erro ao processar login: " + e.getMessage() + "\n");
            return generateResponse(500, "Erro interno");
        }
    }

    private String buscarConsumerIdNoDynamoDB(String cpf) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("cpf", AttributeValue.builder().s(cpf).build());

        GetItemRequest request = GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(key)
                .build();

        GetItemResponse response = dynamoDbClient.getItem(request);

        System.out.println("DynamoDB Response: " + response);

        if (response.hasItem()) {
            Map<String, AttributeValue> item = response.item();
            if (item.containsKey("consumer_id")) {
                return item.get("consumer_id").n();
            } else {
                System.out.println("CPF encontrado, mas sem consumer_id associado.");
            }
        } else {
            System.out.println("Nenhum item encontrado para o CPF: " + cpf);
        }

        return null;
    }

    private String gerarJWT(String cpf, String consumerId) {
        Key key = Keys.hmacShaKeyFor(SECRET_KEY.getBytes(StandardCharsets.UTF_8));

        return Jwts.builder()
                .setSubject(cpf)
                .claim("consumer_id", consumerId)
                .setExpiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    private APIGatewayProxyResponseEvent generateResponse(int statusCode, String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(body);
    }
}
