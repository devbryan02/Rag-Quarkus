package com.bryan.features.chat.records;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * DTO de entrada. Record porque es inmutable y no necesita lógica:
 * solo transporta el JSON {"question": "..."} hacia el endpoint.
 */
public record ChatRequest(@NotNull @NotBlank String question) { }