package dev.frierendb.agent.model;

import com.fasterxml.jackson.databind.JsonNode;

/** A tool as advertised to the model: its name, purpose and JSON Schema for the arguments. */
public record ToolSpec(String name, String description, JsonNode parametersSchema) {}
