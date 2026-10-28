package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Applies ordered todo mutations under the same lock with a single atomic save. */
public final class TodoApplyBatchTool implements CliTool {
    private final TodoWriteTool writer = new TodoWriteTool();

    @Override
    public String id() { return "todo_apply_batch"; }

    @Override
    public String description() {
        return "Apply multiple add, update, or delete operations to the current transcript session's todo list "
                + "in one atomic batch. Operations run in order and the list is saved once; if any operation "
                + "is invalid, no tasks are changed. Use exact task_id values from todoread for update/delete; "
                + "new tasks get generated IDs returned in the result. Each operation uses the same fields as "
                + "todowrite: action, subject, task_description, status, priority, and task_id. "
                + "Unmentioned tasks and fields are preserved. Set session_id only on the batch to target an "
                + "existing older transcript session. Use this to complete one task and start the next in one call.";
    }

    @Override
    public String compactHint() {
        return "Atomically apply ordered todo add/update/delete operations; exact IDs from todoread. "
                + "Any invalid operation leaves the entire list unchanged.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = JsonUtils.standardMapper().createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");
        ObjectNode operations = props.putObject("operations");
        operations.put("type", "array");
        operations.put("minItems", 1);
        operations.put("description", "Ordered add/update/delete operations. update/delete require a real task_id; "
                + "add requires subject. All operations succeed together or none are saved.");
        ObjectNode item = writer.parameterSchema().deepCopy();
        ObjectNode itemProps = (ObjectNode) item.get("properties");
        itemProps.remove(List.of("todos", "session_id"));
        ObjectNode action = (ObjectNode) itemProps.get("action");
        action.put("description", "Action: add, update, or delete.");
        action.putArray("enum").add("add").add("update").add("delete");
        operations.set("items", item);
        props.set("session_id", writer.parameterSchema().path("properties").get("session_id"));
        schema.putArray("required").add("operations");
        return schema;
    }

    /** A batch must never bypass an existing deny/ask rule for todo writes. */
    @Override
    public String permissionKey() { return "todowrite"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        return writer.executeBatch(params, context);
    }
}
