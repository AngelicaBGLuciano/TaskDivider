# Divisor de Tarefas

Distribua tarefas entre a sua equipe de forma equilibrada, direto do navegador.
Feito em **Java puro**: sem frameworks, sem Maven, sem dependências. Só o JDK.

## Funcionalidades

- Cadastro de membros da equipe e de tarefas com esforço estimado (1 a 100 pontos)
- Divisão automática e balanceada, recalculada a cada alteração
- Barras de carga por pessoa para enxergar o equilíbrio de relance
- Validação de entrada no servidor e proteção contra XSS no cliente

## Como rodar

**Requisito:** Java 17 ou superior.

```bash
java TaskDivider.java
```

Abra **http://localhost:8080**. Para usar outra porta:

```bash
java TaskDivider.java 9090
```

### Gerando um `.jar` executável

```bash
javac TaskDivider.java
printf "Main-Class: TaskDivider\n" > manifest.txt
jar cfm divisor.jar manifest.txt *.class
java -jar divisor.jar
```

## Como funciona a divisão

O projeto usa o algoritmo **LPT (Longest Processing Time first)**, uma heurística clássica de balanceamento de carga:

1. Ordena as tarefas da **maior para a menor** em esforço.
2. Entrega cada tarefa ao membro que tem **menos pontos acumulados** no momento.

**Exemplo:** com Ana e Bruno e as tarefas API (8), Login (5), Testes (5), Deploy (3) e Docs (2):

| Membro | Tarefas                | Carga |
|--------|------------------------|-------|
| Ana    | API, Deploy            | 11    |
| Bruno  | Login, Testes, Docs    | 12    |

Complexidade: `O(n log n + n·m)`, com *n* tarefas e *m* membros.
O LPT não garante a divisão perfeita (esse é um problema NP-difícil), mas fica bem próximo do ótimo na prática.

## API REST

As requisições `POST` usam `application/x-www-form-urlencoded`. Toda resposta bem-sucedida devolve o estado completo em JSON, já com o plano calculado.

| Método | Rota                    | Parâmetros          |
|--------|-------------------------|---------------------|
| GET    | `/api/state`            | (nenhum)           |
| POST   | `/api/members/add`      | `name`              |
| POST   | `/api/members/delete`   | `name`              |
| POST   | `/api/tasks/add`        | `title`, `effort`   |
| POST   | `/api/tasks/delete`     | `id`                |
| POST   | `/api/reset`            | (nenhum)           |

```bash
curl -X POST -d "name=Ana" localhost:8080/api/members/add
curl -X POST -d "title=API&effort=8" localhost:8080/api/tasks/add
curl localhost:8080/api/state
```

Erros de validação retornam `400` com `{"error": "mensagem"}`.

## Próximos passos

- [ ] Persistência em arquivo ou SQLite
- [ ] Testes automatizados do algoritmo (JUnit)
- [ ] Separar em pacotes (`domain`, `http`, `web`)
- [ ] Capacidade por membro (pesos diferentes) e dependências entre tarefas
- [ ] Reatribuição manual de tarefas
