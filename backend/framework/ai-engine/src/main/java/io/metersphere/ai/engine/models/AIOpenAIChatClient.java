package io.metersphere.ai.engine.models;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.metersphere.ai.engine.common.AIChatClient;
import io.metersphere.ai.engine.common.AIChatOptions;
import io.metersphere.ai.engine.common.AIModelType;
import io.metersphere.ai.engine.common.AIRegister;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.HashMap;
import java.util.Map;

@AIRegister(AIModelType.OPEN_AI)
@Component
public class AIOpenAIChatClient extends AIChatClient {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ChatClient chatClient(AIChatOptions options) {
        ChatClient.Builder builder = ChatClient.builder(chatModel(options));
        this.addAdvisor(options, builder);
        builder.defaultOptions(this.builderChatOptions(options).build());
        return builder.build();
    }

    @Override
    public ChatModel chatModel(AIChatOptions options) {
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    if (body != null && body.length > 0) {
                        try {
                            Map<String, Object> map = objectMapper.readValue(body, Map.class);
                            map.remove("frequency_penalty");
                            map.remove("logprobs");
                            map.remove("top_logprobs");
                            return execution.execute(request, objectMapper.writeValueAsBytes(map));
                        } catch (Exception e) {
                            return execution.execute(request, body);
                        }
                    }
                    return execution.execute(request, body);
                });

        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(options.getApiKey())
                .baseUrl(options.getBaseUrl())
                .restClientBuilder(restClientBuilder)
                .build();

        HashMap<String, String> headerMap = new HashMap<>();
        headerMap.put("Accept-Encoding", "gzip, deflate");
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().httpHeaders(headerMap)
                        .model(options.getModelType())
                        .build())
                .build();
    }
}
