package com.bryan.features.chat.records;

/**
 * DTO de entrada. Record porque es inmutable y no necesita lógica:
 * solo transporta el JSON {"question": "..."} hacia el endpoint.
 */
public record ChatRequest(String question) {
}