Task: Build Your Own Pet Project with Claude Code

Build a small, working pet project in one week using an AI coding assistant such as Claude Code. The goal is to experience an agentic development loop for a real feature. Focus on tests-first development, reproducible AI-driven suggestions, and clear agent logs that record prompts, AI outputs, and the final accepted code.

Provide a working MVP, unit and integration tests that run in CI, a short demo recording, and a one-page report describing which AI suggestions were accepted, which were rejected, and why. Attach the link to Git repository as well as all artifacts to the submisson.

Description of tasks by domain areas
Library catalog
A minimal service to manage books, authors, and simple reservations. The focus is reliable search and correct filtering under edge cases.
MVP. REST API for CRUD on books and authors, full-text search or simple indexed search by title and author, and a small UI or Swagger page to exercise search and reservation flows.
Tests. Unit tests for CRUD and search ranking, integration test that simulates a reservation lifecycle and concurrent reservation attempt.
Artifacts. Repository with API code, tests, CI passing badge, agent_log.txt and a 3-4 minute demo recording showing search and reservation.
Suggested stack. FastAPI or Express for the backend, SQLite for storage, pytest or Jest, minimal React or Swagger UI for the front end.
Acceptance criteria. Search returns correct ranked results for provided cases. Reservation endpoint enforces simple concurrency rule. Tests pass in CI and agent_log documents at least three AI suggestions with rationale.

Bookstore (catalog + cart)
Small e-commerce style flow focusing on cart behavior and order assembly without payment integration. Emphasize correct cart state and idempotent order creation.
MVP. Product listing, add/remove items to cart, view cart, and create an order record. Provide an API contract and a simple front end to demonstrate the flow.
Tests. Unit tests for cart total and discounts edge cases, integration test covering add→checkout→order creation and idempotency.
Artifacts. Repo with API and UI, test suite, CI results, agent_log with prompts that produced core logic snippets, demo recording.
Suggested stack. Next.js or React front end, Node/Express or FastAPI backend, SQLite or simple JSON storage, Cypress optional for an end-to-end check.
Acceptance criteria. Cart logic handles quantity updates and idempotent checkout. All tests pass in CI and the demo shows a full checkout cycle.

Personal finance manager
Lightweight tracker for income and expenses with categories and a weekly summary. Focus on correct aggregation and category handling.
MVP. API or CLI to add transactions, tag categories, and return weekly totals and category breakdowns. Export results to CSV or JSON.
Tests. Unit tests for aggregation logic and edge cases for overlapping week boundaries, integration test for end-to-end transaction creation and reporting.
Artifacts. Repository with core logic, tests, CI, sample data, agent_log and a short demo showing report generation.
Suggested stack. Django/DRF or FastAPI backend, simple React UI optional, pytest for tests.
Acceptance criteria. Aggregation calculations are correct for sample datasets and edge cases. Tests run green in CI, and the one-page report highlights how AI assisted in producing core functions.

Recipe manager and weekly menu planner
CRUD for recipes and an algorithm to assemble a weekly menu given constraints. Emphasis on constraint handling and reproducibility of generated menus.
MVP. Create and store recipes, tag ingredients and dietary constraints, generate a 7-day menu that respects constraints and produces a grocery list.
Tests. Unit tests for constraint enforcement and menu generation, integration test that covers recipe creation to final export.
Artifacts. Repo with generator code and tests, CI badge, agent_log entries for prompts used to tune generation rules, demo recording.
Suggested stack. Flask or Node backend, simple templated UI, pytest or Jest.
Acceptance criteria. Menu generator respects constraints in test cases and produces reproducible output for the same inputs. Tests pass in CI.

TODO app with time tracking
Task manager that supports start/stop timers and correctly accumulates time across pauses and resumed sessions, with attention to edge cases.
MVP. Create tasks, start/stop timer per task, calculate total time per task, and produce a weekly time summary.
Tests. Unit tests for timer logic including pause/resume boundary conditions, integration test covering a realistic user session.
Artifacts. Repo with backend or client logic, test suite, agent_log showing AI suggestions used to implement accurate time accounting, demo recorded scenario.
Suggested stack. React front end + localStorage or lightweight backend, Jest or pytest for tests.
Acceptance criteria. Timer logic is robust to pauses and resumed sessions. CI green and agent_log contains clear mapping from prompt to accepted code.

Weather dashboard (mocked API)
Front end that displays current conditions and a short forecast for a chosen city, using either a mocked API or a public API with clear keys handling. Emphasize reliable parsing and graceful failure handling.
MVP. City search, display current weather and 3-day forecast, save a list of favorite cities. Use mocks for tests to avoid external API flakiness.
Tests. Unit tests for data parsing and integration tests using mocked responses.
Artifacts. Repo with UI, mocked backends for CI, tests, agent_log and demo.
Suggested stack. React or Vue front end, MSW or similar for mocking, Jest for tests.
Acceptance criteria. UI renders expected data from mock payloads, tests pass in CI, demo demonstrates failure handling for bad responses.

Recommendation microservice (movies or books)
Small rule-based recommender that returns ranked items based on genre, simple user history, or popularity heuristics. Focus on explainability of recommendations.
MVP. /recommend endpoint accepting simple profile and returning ranked results with a short explanation for each recommendation.
Tests. Unit tests that validate ranking logic given test inputs, integration test for end-to-end recommendation request.
Artifacts. Repo with service, tests, CI results, agent_log with prompts used to craft ranking heuristics, short demo.
Suggested stack. FastAPI or Express, SQLite or in-memory dataset, pytest or Jest.
Acceptance criteria. Recommendations match expectations for test scenarios and explanations are present. CI green and agent_log documents the prompt iterations.