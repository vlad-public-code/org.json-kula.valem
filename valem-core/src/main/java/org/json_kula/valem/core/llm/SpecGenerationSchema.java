package org.json_kula.valem.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON Schemas describing the shape of a {@code ModelSpec} and a {@code SpecEvolution}, for use as a
 * provider "structured output" {@code response_format} (OpenAI-compatible {@code json_schema} mode).
 *
 * <p>These are deliberately <b>permissive</b> (non-strict): a {@code ModelSpec} embeds an arbitrary
 * JSON Schema in its own {@code schema} field (and an open {@code viewDefinition}/{@code payload}),
 * which cannot be expressed under OpenAI strict mode (strict requires {@code additionalProperties:
 * false} and a complete {@code required} list on every object). So the schema fixes the well-known
 * structure — the section names and the per-record fields the model routinely gets wrong — while
 * leaving the genuinely free-form parts as open objects. It is shape guidance, not a hard contract;
 * the {@code ModelSpecValidator} remains the source of truth.
 */
public final class SpecGenerationSchema {

    private SpecGenerationSchema() {}

    // A derivation/constraint/etc. expr is always a JSONata string; paths are JsonPath strings.
    private static final String DERIVATION_ITEM = """
            {"type":"object",
             "properties":{"path":{"type":"string"},"expr":{"type":"string"},
                           "evaluation":{"type":"string","enum":["eager","lazy"]}},
             "required":["path","expr"]}""";

    // A default-value rule: a container path ($ / object / $.items[*]) and a JSONata object expr.
    private static final String DEFAULT_VALUE_ITEM = """
            {"type":"object",
             "properties":{"path":{"type":"string"},"expr":{"type":"string"},
                           "description":{"type":"string"}},
             "required":["path","expr"]}""";

    private static final String META_DERIVATION_ITEM = """
            {"type":"object",
             "properties":{"path":{"type":"string"},"property":{"type":"string"},"expr":{"type":"string"}},
             "required":["path","property","expr"]}""";

    private static final String CONSTRAINT_ITEM = """
            {"type":"object",
             "properties":{"id":{"type":"string"},"expr":{"type":"string"},"message":{"type":"string"},
                           "policy":{"type":"string","enum":["rollback","flag"]},"path":{"type":"string"}},
             "required":["id","expr"]}""";


    private static final String TEST_ITEM = """
            {"type":"object",
             "properties":{"description":{"type":"string"},"given":{"type":"object"},"expect":{"type":"object"}},
             "required":["expect"]}""";

    // An effect: the core emits it as data; a shell (executor) runs it. Fields are executor-specific
    // (all optional beyond id/executor/trigger) — the validator enforces the per-executor requirements.
    private static final String EFFECT_ITEM = """
            {"type":"object",
             "properties":{"id":{"type":"string"},
                           "executor":{"type":"string","enum":["caller","server","llm","timer"]},
                           "trigger":{"type":"string"},"dedupeKey":{"type":"string"},"statusPath":{"type":"string"},
                           "emit":{"type":"string"},"payload":{"type":"object"},
                           "request":{"type":"object"},"requests":{"type":"string"},
                           "prompt":{"type":"string"},"responseSchema":{"type":"object"},
                           "at":{"type":"string"},"afterMs":{"type":"string"},
                           "response":{"type":"object"},"policy":{"type":"object"}},
             "required":["id","executor","trigger"]}""";

    private static final String MODEL_SPEC_SCHEMA = """
            {"type":"object",
             "properties":{
               "id":{"type":"string"},
               "version":{"type":"string"},
               "schema":{"type":"object"},
               "constants":{"type":"object"},
               "defaultValues":{"type":"array","items":""" + DEFAULT_VALUE_ITEM + """
            },
               "derivations":{"type":"array","items":""" + DERIVATION_ITEM + """
            },
               "metaDerivations":{"type":"array","items":""" + META_DERIVATION_ITEM + """
            },
               "constraints":{"type":"array","items":""" + CONSTRAINT_ITEM + """
            },
               "effects":{"type":"array","items":""" + EFFECT_ITEM + """
            },
               "tests":{"type":"array","items":""" + TEST_ITEM + """
            },
               "viewDefinition":{"type":"object"}
             },
             "required":["id","schema"]}""";

    private static final String SPEC_EVOLUTION_SCHEMA = """
            {"type":"object",
             "properties":{
               "newVersion":{"type":"string"},
               "newSchema":{"type":"object"},
               "newViewDefinition":{"type":"object"},
               "upsertDerivations":{"type":"array","items":""" + DERIVATION_ITEM + """
            },
               "removeDerivations":{"type":"array","items":{"type":"string"}},
               "upsertMetaDerivations":{"type":"array","items":""" + META_DERIVATION_ITEM + """
            },
               "removeMetaDerivations":{"type":"array","items":{"type":"string"}},
               "upsertDefaultValues":{"type":"array","items":""" + DEFAULT_VALUE_ITEM + """
            },
               "removeDefaultValues":{"type":"array","items":{"type":"string"}},
               "newConstants":{"type":"object"},
               "upsertConstraints":{"type":"array","items":""" + CONSTRAINT_ITEM + """
            },
               "removeConstraints":{"type":"array","items":{"type":"string"}},
               "upsertEffects":{"type":"array","items":""" + EFFECT_ITEM + """
            },
               "removeEffects":{"type":"array","items":{"type":"string"}},
               "backfill":{"type":"object"},
               "expectedVersion":{"type":"string"},
               "upsertSchemaDefs":{"type":"object"},
               "removeSchemaDefs":{"type":"array","items":{"type":"string"}},
               "upsertSchemaNodes":{"type":"array","items":{"type":"object",
                 "properties":{"path":{"type":"string"},"schema":{"type":"object"},"required":{"type":"boolean"}},
                 "required":["path","schema"]}},
               "removeSchemaNodes":{"type":"array","items":{"type":"string"}},
               "newDefaultView":{"type":"string"},
               "upsertViews":{"type":"array","items":{"type":"object"}},
               "removeViews":{"type":"array","items":{"type":"string"}},
               "upsertComponents":{"type":"array","items":{"type":"object",
                 "properties":{"viewId":{"type":"string"},"parentId":{"type":"string"},
                   "beforeId":{"type":"string"},"component":{"type":"object"}},
                 "required":["viewId","component"]}},
               "removeComponents":{"type":"array","items":{"type":"object",
                 "properties":{"viewId":{"type":"string"},"componentId":{"type":"string"}},
                 "required":["viewId","componentId"]}},
               "upsertConstants":{"type":"object"},
               "removeConstants":{"type":"array","items":{"type":"string"}}
             }}""";

    /** The permissive JSON Schema for a full {@code ModelSpec}. */
    public static JsonNode modelSpec(ObjectMapper mapper) {
        return parse(mapper, MODEL_SPEC_SCHEMA);
    }

    /** The permissive JSON Schema for a {@code SpecEvolution} diff. */
    public static JsonNode specEvolution(ObjectMapper mapper) {
        return parse(mapper, SPEC_EVOLUTION_SCHEMA);
    }

    private static JsonNode parse(ObjectMapper mapper, String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            // The schema text is a compile-time constant — a parse failure is a programming error.
            throw new IllegalStateException("Invalid built-in spec JSON Schema", e);
        }
    }
}
