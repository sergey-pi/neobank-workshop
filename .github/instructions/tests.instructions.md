---
applyTo: "src/test/java/**/*.java"
---

# Test Instructions (Spring Boot 4)

## Mandatory test coverage

**Every code change must include or update tests.** This is a non-negotiable project rule.

- New service method → add a test case for the happy path AND at least one error case
- Bug fix → add a test that would have caught the bug (regression test)
- New exception type or HTTP mapping → assert the exact status code and `$.code` field
- Run `./mvnw test -pl <service>` locally before pushing

## Setup Pattern

`TestRestTemplate` and `@AutoConfigureMockMvc` **do not exist** in Spring Boot 4.
Always use this pattern:

```java
@SpringBootTest
class MyServiceIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;   // ← autowire, don't construct manually

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }
}
```

## ObjectMapper

Spring Boot 4 uses **Jackson 3.x** (`tools.jackson.core:jackson-databind`).
Import from the new package — `com.fasterxml.jackson` no longer exists in this stack:

```java
// ✅ correct
import tools.jackson.databind.ObjectMapper;

// ❌ wrong — Jackson 2.x is not on the classpath
import com.fasterxml.jackson.databind.ObjectMapper;
```

`JavaTimeModule` is **not needed** — Jackson 3.x handles `LocalDate`/`LocalDateTime` natively when autowired from the Spring context.

Never construct `ObjectMapper` manually in tests. Use `@Autowired ObjectMapper`.

## Test Data

Tests run against a **real PostgreSQL** database and are **not rolled back** between runs.
Always use unique values to avoid conflicts on re-runs:

```java
// ✅
String email = "user+" + UUID.randomUUID() + "@example.com";
String reference = "REF-" + UUID.randomUUID();

// ❌ — will fail on second run
String email = "alice@example.com";
```

## Assertions

Use `MockMvc` result matchers:

```java
mockMvc.perform(post("/api/v1/users/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(request)))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.email").value(email))
    .andExpect(jsonPath("$.id").isNotEmpty());
```

For RFC 7807 error responses, assert the `code` field:

```java
.andExpect(status().isConflict())                      // 409
.andExpect(jsonPath("$.code").value("CONFLICT"))
.andExpect(status().isUnprocessableEntity())           // 422
.andExpect(jsonPath("$.code").value("UNPROCESSABLE"))
```

## Reading JSON from Response

When you need to extract a value from a response body, use the autowired `ObjectMapper`:

```java
MvcResult result = mockMvc.perform(post(...)).andReturn();
UUID id = UUID.fromString(
    objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
```
