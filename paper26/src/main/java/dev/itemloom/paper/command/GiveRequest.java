package dev.itemloom.paper.command;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

/** One command request; JSON values are strings so no implicit type conversions reach scripts. */
public record GiveRequest(
        String id, int count, boolean independent, Map<String, String> parameters) {
    public GiveRequest {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("缺少物品 ID");
        if (count < 1 || count > 256) throw new IllegalArgumentException("数量范围为 1–256");
        parameters = Map.copyOf(parameters);
    }

    public static GiveRequest parse(String input) {
        String[] parts = input.trim().split("\\s+", 4);
        int count = parts.length < 2 ? 1 : Integer.parseInt(parts[1]);
        String mode = parts.length < 3 ? "stack" : parts[2];
        if (!mode.equals("stack") && !mode.equals("roll"))
            throw new IllegalArgumentException("生成模式为 stack 或 roll");
        Map<String, String> values = new LinkedHashMap<>();
        if (parts.length == 4) {
            if (parts[3].length() > 16_384)
                throw new IllegalArgumentException("参数 JSON 超过 16 Ki 字符");
            try (JsonReader json = new JsonReader(new StringReader(parts[3]))) {
                json.setStrictness(Strictness.STRICT);
                json.beginObject();
                while (json.hasNext()) {
                    String key = json.nextName();
                    if (key.isBlank() || values.containsKey(key))
                        throw new IllegalArgumentException("参数名为空或重复: " + key);
                    if (json.peek() != JsonToken.STRING)
                        throw new IllegalArgumentException("参数值必须为 JSON 字符串: " + key);
                    values.put(key, json.nextString());
                }
                json.endObject();
                if (json.peek() != JsonToken.END_DOCUMENT)
                    throw new IllegalArgumentException("JSON 后有多余内容");
            } catch (java.io.IOException error) {
                throw new IllegalArgumentException("参数不是有效 JSON 对象", error);
            }
        }
        return new GiveRequest(parts[0], count, mode.equals("roll"), values);
    }
}
