# TODO — вопрос про крестьянина (state-space planning puzzle)

**Дата обновления:** 2026-11-09  
**Статус:** pipeline проверен, все 3 узких места найдены; coordination update pending

---

## Что проверено (реальные данные, не теория)

### Pipeline
```
User question → Knowledge.isConstraints → GridText.looksLike → GridText.infer
  → PuzzleText.parse → PuzzleDomain.copier → PuzzleDomain.rules
  → Step.Constraints → Puzzles.read → constraints() → Solver.solve
```

### Результаты на вопросе про козу/капусту/волка

| Шаг | Результат | Статус |
|---|---|---|
| `GridText.counts()` | `Vector()` | ✗ нет "N houses" |
| `GridText.looksLike()` | `false` | ✗ нет "houses" |
| `GridText.infer()` | Left — house count | ✗ |
| `PuzzleText.parse()` | `ready = false` | ✗ |
| `PuzzleDomain.rules` reader | Все clues `form = NONE` | ✗ нет reader'а для "наедине" |
| `Knowledge.isConstraints()` | `false` | → идёт в Author mode |

**Вывод:** ни один из existing pipeline-шагов не обрабатывает этот вопрос. 100% провал.

---

## Что сделать (в порядке)

### 1. Route model output through existing authored() path
**Файл:** `core/src/main/scala/okay/desk/know/Knowledge.scala` (обновить `authored()`)

**Что:** Модель 28-слой генерирует JSON — `{"constraints": [...]}` или `{"plan": {...}}`. `authored()` уже принимает эти форматы. Нужно только убедиться что handler принимает `Step.Constraints` от Author режима, не только от GridText.

### 2. State-space solver (BFS)
**Файл:** новый `core/src/main/scala/okay/desk/exec/StateSpace.scala` (или в `puzzle/`)

**Что:** BFS по состояниям (left bank → right bank), безопасность проверяется через `Planning.Result`. Интеграция с `Planning.scala` (owned by other agent) — потребляем `Result`, не редактируем файл.

### 3. Integration: Author → CSP/StateSpace dispatch
**Файл:** `Computation.scala` (добавить dispatch логику), `Puzzles.scala` (handler)

**Пайплайн:**
```
prose question → GridText.infer (categories, if available)
  → if no grid but items + "cannot be left" → FarmingPuzzle
  → read constraints via PuzzleDomain.rules (differ reader)
  → StateSpace.solve → path output
```

---

## Где смотреть (файлы)

| Файл | Что |
|---|---|
| `know/Planning.scala` | Переходная модель (states, moves, guards) — owned by other agent |
| `exec/Computation.scala` | `PlanModel` job → `Planning.Result` — добавить dispatch |
| `know/Knowledge.scala` | `authored()`, `isConstraints()` — routing from prose |
| `puzzle/Puzzle.scala` | `Constraint.Differ` — уже есть |
| `puzzle/Solver.scala` | CSP solver — route CSP here |
| `puzzles.scala` (UI) | Handler — add state-space path |

## Что уже есть (готовые компоненты)

- `Constraint.Differ(a, b)` — уже есть в `Puzzle.scala` line 19
- `Planning.Result` — state-transition model from `know/Planning.scala`
- `Knowledge.Step.Formal(plan)` / `Step.Plan(program)` — Author mode outputs
- `Knowledge.authored(text)` — parses model JSON into steps
- `Computation.Job.Solve(puzzle, bound)` → `Solver.Outcome` — CSP solver
- `Computation.Job.PlanModel(program)` → `Planning.Result` — state-space planning handler

**Осталось:** reader для "не остаётся с X наедине" + state-space solver.
