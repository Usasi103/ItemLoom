package dev.itemloom.paper.command;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record GiveRequest(
        String id, int count, boolean independent, Map<String, String> parameters) {
    public GiveRequest {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("缺少物品 ID");
        if (count < 1 || count > 256) throw new IllegalArgumentException("数量范围为 1–256");
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
    }

    public static GiveRequest parse(String input) {
        String[] parts = input.trim().split("\\s+", 4);
        String id = parts[0];
        int count = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
        String mode = parts.length > 2 ? parts[2] : "stack";
        if (!mode.equals("stack") && !mode.equals("roll"))
            throw new IllegalArgumentException("生成模式为 stack 或 roll");
        Map<String, String> values = parts.length > 3 ? parameters(parts[3]) : Map.of();
        return new GiveRequest(id, count, mode.equals("roll"), values);
    }

    private static Map<String, String> parameters(String json) {
        if (json.length() > 16384) throw new IllegalArgumentException("参数 JSON 超过 16 Ki 字符");
        var values = new LinkedHashMap<String, String>();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (key.isBlank() || values.containsKey(key))
                    throw new IllegalArgumentException("参数名为空或重复: " + key);
                if (reader.peek() != JsonToken.STRING)
                    throw new IllegalArgumentException("参数值必须为 JSON 字符串: " + key);
                values.put(key, reader.nextString());
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT)
                throw new IllegalArgumentException("JSON 后有多余内容");
        } catch (IOException | IllegalStateException failure) {
            throw new IllegalArgumentException("参数不是有效 JSON 对象", failure);
        }
        return values;
    }
}
