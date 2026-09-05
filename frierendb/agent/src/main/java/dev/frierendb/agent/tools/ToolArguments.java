package dev.frierendb.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class ToolArguments {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolArguments() {}

    static String requireString(JsonNode arguments, String name, int maxLength) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ToolException("Missing required string argument '" + name + "'");
        }
        String text = value.asText();
        if (text.length() > maxLength) {
            throw new ToolException("Argument '" + name + "' is too long (limit " + maxLength + " characters)");
        }
        return text;
    }

    /** JSON Schema for an object with a single required string property. */
    static ObjectNode singleStringSchema(String name, String description) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject(name)
                .put("type", "string")
                .put("description", description);
        schema.putArray("required").add(name);
        schema.put("additionalProperties", false);
        return schema;
    }

    static ObjectNode emptySchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        schema.put("additionalProperties", false);
        return schema;
    }
}
