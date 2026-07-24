package dio.budgeting.infrastructure.http;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI budgetingOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Budgeting API")
                        .version("1.0.0")
                        .description("API de orçamento com comandos de voz processados via Spring AI"));
    }
}