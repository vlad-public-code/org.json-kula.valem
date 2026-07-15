package org.json_kula.valem.api.llm;

import org.json_kula.valem.core.llm.LlmClient.ToolCall;
import org.json_kula.valem.core.llm.LlmClient.ToolDefinition;
import org.json_kula.valem.core.llm.LlmClient.ToolExecutor;
import org.json_kula.valem.core.llm.WebTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bundles several {@link WebTool}s (e.g. {@code web_search} + {@code web_fetch}) so the LLM is
 * offered all of their definitions at once. {@link #newExecutor()} builds one fresh executor per
 * sub-tool and routes each {@link ToolCall} to the matching one by tool name; every sub-tool keeps
 * its own independent per-session budget. {@link WebFetchFact}s are aggregated across sub-tools.
 */
public final class CompositeWebTool implements WebTool {

    private final List<WebTool> tools;

    public CompositeWebTool(List<WebTool> tools) {
        if (tools == null || tools.isEmpty())
            throw new IllegalArgumentException("CompositeWebTool requires at least one tool");
        this.tools = List.copyOf(tools);
    }

    @Override
    public List<ToolDefinition> definitions() {
        return tools.stream().flatMap(t -> t.definitions().stream()).toList();
    }

    @Override
    public ToolExecutor newExecutor() {
        Map<String, ToolExecutor> byName = new LinkedHashMap<>();
        List<ToolExecutor> all = new ArrayList<>();
        for (WebTool tool : tools) {
            ToolExecutor executor = tool.newExecutor();
            all.add(executor);
            for (ToolDefinition def : tool.definitions())
                byName.put(def.name(), executor);
        }
        return new RoutingExecutor(byName, all);
    }

    /** Routes calls by tool name and aggregates facts from any fact-collecting sub-executors. */
    static final class RoutingExecutor implements ToolExecutor, FactProvider {

        private final Map<String, ToolExecutor> byName;
        private final List<ToolExecutor>        all;

        RoutingExecutor(Map<String, ToolExecutor> byName, List<ToolExecutor> all) {
            this.byName = byName;
            this.all    = all;
        }

        @Override
        public String execute(ToolCall call) {
            ToolExecutor executor = byName.get(call.name());
            if (executor == null)
                return "[Unknown tool: " + call.name() + "]";
            return executor.execute(call);
        }

        @Override
        public List<WebFetchFact> facts() {
            return all.stream()
                    .filter(FactProvider.class::isInstance)
                    .flatMap(e -> ((FactProvider) e).facts().stream())
                    .toList();
        }
    }
}
