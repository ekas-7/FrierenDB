package dev.frierendb.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.frierendb.agent.model.ToolSpec;

public interface Tool {

    ToolSpec spec();

    /** Runs the tool. Throws {@link ToolException} for problems the model can fix by retrying differently. */
    String execute(JsonNode arguments);
}
