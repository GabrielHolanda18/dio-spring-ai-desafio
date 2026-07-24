# DIO Spring Boot - Final Project 05: Spring AI (budgeting)

Desafio de Projeto da trilha Spring Boot da DIO (parceria NTT Data) - módulo
final da trilha, evoluindo uma API de orçamento que usa **Spring AI** para
processar comandos de voz relacionados a transações financeiras.

## Sobre o desafio

O projeto base já vinha pronto (fork da trilha), implementando o fluxo
principal: receber um áudio, transcrever para texto, usar um `ChatClient` com
Tool Calling para decidir qual ação executar, persistir/consultar a transação,
e devolver a resposta também em áudio.

Meu trabalho neste desafio foi entender esse fluxo de ponta a ponta e escolher
**uma melhoria pequena, mas que realmente protegesse a aplicação** - em vez de
tentar abraçar várias ideias ao mesmo tempo, como o próprio enunciado do
desafio recomendava.

## Melhoria escolhida: validação de domínio na `Transaction`

Antes da melhoria, a entidade `Transaction` aceitava qualquer valor no
construtor - inclusive descrição vazia ou valor zerado/negativo. Isso é
particularmente arriscado nesse projeto porque **duas portas de entrada
diferentes** criam uma `Transaction`:

- o endpoint REST tradicional (`POST /transactions`);
- o fluxo de comando de voz, no qual a própria IA (via Tool Calling) decide
  chamar `PersistTransactionUseCase.execute(...)` diretamente, sem passar por
  nenhuma validação de request HTTP (`@Valid`, `@RequestBody`, etc).

Ou seja: uma transcrição de áudio malfeita, ou uma decisão estranha do modelo,
poderia gerar uma transação inválida sem que nenhuma camada barrasse isso.

**Decisão:** em vez de validar no controller (que só cobre o fluxo REST),
coloquei a validação no **construtor da entidade de domínio**. Isso garante
que os dois fluxos fiquem protegidos pela mesma regra, sem duplicar lógica em
lugares diferentes.

### O que foi adicionado/alterado

| Arquivo | Tipo | O que faz |
|---|---|---|
| `domain/InvalidTransactionException.java` | novo | Exceção de domínio para dados de transação inválidos |
| `domain/Transaction.java` | alterado | Construtor passou a validar `description` (não vazia) e `amount` (> 0) antes de criar o objeto |
| `infrastructure/http/GlobalExceptionHandler.java` | novo | Mapeia `InvalidTransactionException` para `400 Bad Request` no fluxo REST |

Um detalhe de design: o construtor `@AllArgsConstructor` (usado por
`TransactionEntity.toDomain()`, ao reconstruir uma transação vinda do banco)
**não passa pela validação** - e isso é intencional. A regra existe para
proteger a *criação* de uma transação nova, não para revalidar dados que já
foram persistidos anteriormente.

## Sobre o histórico de commits

Segui commits pequenos e incrementais, tentando refletir a ordem real de
raciocínio da implementação (exceção -> validação no domínio -> tratamento
HTTP -> teste), em vez de um único commit grande com tudo junto.

Vale registrar com transparência: nem tudo saiu limpo de primeira. Em um
momento eu esqueci de chamar o `validate(...)` dentro do construtor de
`Transaction` (o código compilava, mas a regra simplesmente não rodava);
em outro, colei sem querer o arquivo errado (`TransactionController`) num
commit que deveria conter só a exceção de domínio, aproveitando uma mensagem
que já tinha usado antes. Os dois casos foram corrigidos - o primeiro com um
commit de correção (`fix:`) separado, o segundo com `git commit --amend` para
corrigir a mensagem sem reescrever o conteúdo (só possível porque os commits
ainda não tinham sido enviados ao GitHub). Prefiro manter esse histórico
"real" a reescrever tudo para parecer perfeito - também é aprendizado.

## Arquitetura do fluxo (contexto herdado do projeto base)

```
Cliente envia áudio (multipart)
  -> TranscriptionModel.transcribe()               [áudio vira texto]
  -> ChatClient.prompt().user(texto).call()         [IA decide qual @Tool chamar]
       -> PersistTransactionUseCase.execute(...)    [ou ListTransactionsByCategoryUseCase]
            -> new Transaction(...)                 [<- validação de domínio entra aqui]
            -> TransactionRepository.save(...)
  -> TextToSpeechModel.call(textoDeResposta)         [texto vira áudio de novo]
  -> resposta em audio/mp3
```

O ponto-chave para entender a melhoria: o `ChatClient` foi configurado com
`.defaultTools(persistTransactionUseCase, listTransactionsByCategoryUseCase)`
- ou seja, é o próprio modelo de IA (via Spring AI) quem decide, a partir da
  descrição em texto de cada método `@Tool`, qual `UseCase` chamar e com quais
  parâmetros. Isso significa que uma exceção lançada dentro de
  `PersistTransactionUseCase` também pode ser lançada **a partir de uma decisão
  da IA**, não só de uma requisição HTTP direta - daí a necessidade de validar
  no domínio, e não só no controller.

## Como testar

### Teste unitário (recomendado - não exige chave de API, banco ou Docker)

```bash
./gradlew test --tests "dio.budgeting.domain.TransactionTest"
```

O `TransactionTest` valida a entidade `Transaction` isoladamente (sem Spring,
sem banco, sem chamada externa nenhuma):

- criação de uma transação com dados válidos;
- rejeição de uma transação com descrição vazia;
- rejeição de uma transação com valor zero ou negativo.

### Teste manual via REST

Com a aplicação rodando, envie um payload inválido:

```json
POST /transactions
{
  "description": "",
  "category": "GROCERIES",
  "amount": 0
}
```

Resposta esperada: `400 Bad Request`, com a mensagem de erro da validação.

Com dados válidos:

```json
POST /transactions
{
  "description": "mercado",
  "category": "GROCERIES",
  "amount": 8000
}
```

Resposta esperada: `201 Created`.

### O que não foi testado nesta entrega

O fluxo de voz (`POST /transactions/ai`) tentando forçar um valor inválido via
comando falado não foi validado ponta a ponta nesta entrega, por depender de
uma `OPENAI_API_KEY` ativa (custo de créditos por chamada). A proteção de
domínio continua valendo para esse fluxo pela mesma razão explicada acima
(a exceção é lançada dentro do `PersistTransactionUseCase`, independente de
quem o chamou), mas o comportamento exato de como o `ChatClient` reage a uma
`RuntimeException` lançada por uma tool ainda é um ponto em aberto para uma
próxima iteração.

## Desafios enfrentados

### Docker Desktop indisponível ao rodar a aplicação

Ao tentar subir a aplicação pela primeira vez, o Spring Boot falhou logo no
início com:

```
ProcessExitException: 'docker version --format {{.Client.Version}}' failed
failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine
```

O projeto usa o recurso de **Docker Compose Support** do Spring Boot: ao
detectar um `compose.yml` na raiz (que sobe um MySQL), o Spring tenta subir os
containers automaticamente antes de iniciar. No meu ambiente, o Docker
Desktop não estava rodando (e travava ao tentar abrir), então a aplicação
nunca chegava a subir. Como alternativa **apenas para desenvolvimento local**,
desativei esse comportamento (`spring.docker.compose.enabled=false`) e
configurei um banco H2 em memória, permitindo rodar e testar a aplicação sem
depender de infraestrutura externa. Essa mudança foi mantida propositalmente
**fora dos commits enviados**, para não alterar a configuração oficial do
projeto (que espera MySQL via Docker) para quem for avaliar.

### `ClassNotFoundException` mesmo com o `.class` presente no disco

Depois de escrever o teste unitário, o Gradle falhava ao executá-lo com
`java.lang.ClassNotFoundException: dio.budgeting.domain.TransactionTest` -
mesmo confirmando, via `dir`, que o arquivo `TransactionTest.class` existia
fisicamente na pasta `build/classes/java/test/...`. Descartei primeiro a
hipótese óbvia (arquivo não salvo, pacote errado) comparando a estrutura de
pastas com o `package` declarado - estava tudo correto.

O que expôs a causa real foi rodar `./gradlew test` **sem nenhum filtro**:
todos os testes do projeto falhavam com o mesmo erro, incluindo o
`BudgetingApplicationTests`, que nunca tinha sido tocado. Isso descartou
qualquer problema no meu código e apontou para algo no ambiente. O caminho do
projeto continha um caractere acentuado (`Área de Trabalho`) e estava dentro
de uma pasta sincronizada pelo OneDrive - combinação conhecida por causar
falhas na conversão do caminho para URI ao montar o classpath do processo de
teste, fazendo com que a pasta inteira de classes compiladas ficasse
invisível para o "test worker", mesmo existindo no disco. Mover o projeto
inteiro para um caminho simples (`C:\dev\...`, sem acento e fora do OneDrive)
resolveu o problema de forma definitiva.

### Terminal externo usando uma versão antiga do Java

Ao tentar rodar `./gradlew` pelo PowerShell (fora da IDE), o build falhava
com `Gradle requires JVM 17 or later to run. Your build is currently
configured to use JVM 8`. A IDE conseguia rodar o projeto normalmente porque
usa o toolchain do Gradle (Java 25) internamente, mas o terminal externo
dependia da variável de ambiente `JAVA_HOME` do Windows, que ou apontava para
um Java 8 antigo, ou nem estava definida. A solução foi localizar o caminho
do JDK 25 já usado pela IDE (`Project Structure -> SDKs`) e configurar
`JAVA_HOME` no Windows apontando para ele, permitindo que o mesmo comando
funcionasse de forma consistente tanto pela IDE quanto por um terminal
externo.

## O que aprendi

- Onde validação de domínio deve viver quando existe mais de um ponto de
  entrada para a mesma regra de negócio (nesse caso, REST e Tool Calling de
  IA) - e por que colocá-la no domínio evita duplicar a mesma verificação em
  cada camada de entrada.
- Como o Spring AI conecta a decisão de um modelo de linguagem a código Java
  real: o `ChatClient` recebe uma lista de tools (`@Tool`) com suas
  descrições, e é o próprio modelo, na OpenAI, quem decide qual método
  invocar e com quais parâmetros - o roteamento não é escrito à mão.
- A diferença prática entre testes unitários (rápidos, sem dependências
  externas, seguros de rodar sempre) e testes de integração (`IT`), que
  dependem de credenciais e serviços externos como a API da OpenAI, e por
  isso ficam habilitados condicionalmente (`@EnabledIfEnvironmentVariable`).
- Que um `ClassNotFoundException` nem sempre é sobre o código: caminhos de
  projeto com caracteres acentuados e pastas sincronizadas por serviços como
  o OneDrive podem causar falhas silenciosas e difíceis de diagnosticar em
  ferramentas de build no Windows.
- Fluxo de trabalho com Git em uma feature pequena: commits incrementais,
  correção de um commit incompleto com um novo commit `fix:`, e uso de
  `git commit --amend` para corrigir uma mensagem antes do push.

## Estrutura do projeto (módulo `budgeting`)

```
05-spring-ai/
├── src/main/java/dio/budgeting/
│   ├── domain/
│   │   ├── Transaction.java
│   │   ├── TransactionId.java
│   │   ├── Category.java
│   │   ├── TransactionRepository.java
│   │   └── InvalidTransactionException.java   -> novo
│   ├── application/
│   │   ├── PersistTransactionUseCase.java      -> expõe @Tool para a IA
│   │   ├── ListTransactionsByCategoryUseCase.java -> expõe @Tool para a IA
│   │   ├── input/PersistTransactionInput.java
│   │   └── output/TransactionOutput.java
│   └── infrastructure/
│       ├── http/
│       │   ├── TransactionController.java      -> REST + endpoint de voz
│       │   └── GlobalExceptionHandler.java      -> novo
│       └── persistence/
│           ├── entity/TransactionEntity.java
│           └── repository/JpaTransactionRepository.java
└── src/test/java/dio/budgeting/
    ├── domain/TransactionTest.java              -> novo (teste unitário)
    └── (testes de integração pré-existentes: OpenAiChatClientIT, OpenAiChatModelIT,
        OpenAiSpeechModelIT, OpenAiTranscriptionModelIT, ToolCallingIT)
```

## Como rodar

Configuração oficial do projeto (via Docker Compose, MySQL):

```bash
export OPENAI_API_KEY="sua_chave_aqui"
./gradlew bootRun
```

Requer Docker Desktop ativo, já que o `compose.yml` sobe um MySQL
automaticamente através do Docker Compose Support do Spring Boot.

Para rodar os testes:

```bash
./gradlew test
```

Os testes de integração (`*IT`) só executam se `OPENAI_API_KEY` estiver
definida no ambiente; caso contrário, são pulados automaticamente. O teste
unitário `TransactionTest` roda sempre, independentemente disso.

## Tecnologias

- Java 25
- Spring Boot 4.0.5
- Spring AI 2.0.0-M4 (ChatClient, Tool Calling, TranscriptionModel, TextToSpeechModel)
- Spring Data JPA + MySQL (via Docker Compose)
- Lombok
- JUnit 5 + AssertJ

## Considerações finais

Esse desafio me fez entender, na prática, como uma aplicação Spring Boot
"tradicional" se conecta a um modelo de IA sem que a IA vire uma dependência
espalhada pelo código: os `UseCase` não sabem que existe uma IA por trás,
apenas expõem métodos anotados com `@Tool`; quem decide chamar cada um é o
modelo, por trás do `ChatClient`. A melhoria implementada foi pequena de
propósito - uma validação de domínio - mas exigiu entender essa arquitetura a
fundo para escolher o lugar certo de aplicá-la (o domínio, não o controller),
de forma que protegesse os dois pontos de entrada da aplicação com uma única
regra.

Boa parte do tempo desse desafio não foi gasta escrevendo a melhoria em si,
mas debugando o ambiente (Docker, versão de JVM no terminal, e um
`ClassNotFoundException` causado por um caminho de pasta com acento) - o que
acabou sendo, também, um aprendizado real sobre como isolar a causa raiz de
um problema em vez de tentar corrigir sintomas.

Próximos passos possíveis para quem quiser evoluir esse módulo: testar o
fluxo de voz de ponta a ponta forçando um valor inválido por comando falado,
adicionar novas ferramentas de consulta financeira (soma por categoria, por
período), e criar testes de integração adicionais para o
`TransactionController` usando um mock do `ChatClient`.