package com.bryan.features.chat.records;

/**
 * DTO de salida. Separado de ChatRequest aunque ambos sean "solo un String"
 * porque request y response son conceptos distintos -- si más adelante
 * agregas, por ejemplo, las fuentes/chunks usados (citations), solo tocas
 * este record sin afectar el contrato de entrada.
 */
public record ChatResponse(String answer) {
}