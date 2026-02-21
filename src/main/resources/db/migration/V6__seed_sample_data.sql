-- Sample authors
INSERT INTO authors (first_name, last_name, bio, created_at, updated_at) VALUES
  ('Joshua', 'Bloch', 'Former Google Chief Java Architect', now(), now()),
  ('Brian', 'Goetz', 'Java Language Architect at Oracle', now(), now()),
  ('Martin', 'Fowler', 'Author and software development thought leader', now(), now()),
  ('Robert', 'Martin', 'Known as Uncle Bob, author of Clean Code', now(), now()),
  ('Kent', 'Beck', 'Creator of Extreme Programming and TDD', now(), now());

-- Sample books
INSERT INTO books (title, isbn, description, published_year, version, created_at, updated_at) VALUES
  ('Effective Java', '9780134685991', 'A comprehensive guide to Java programming best practices covering language features, design patterns, and common pitfalls.', 2018, 0, now(), now()),
  ('Java Concurrency in Practice', '9780321349606', 'Definitive guide to writing concurrent programs in Java, covering thread safety, performance, and testing.', 2006, 0, now(), now()),
  ('Refactoring', '9780201485677', 'Improving the design of existing code through systematic refactoring techniques.', 1999, 0, now(), now()),
  ('Clean Code', '9780132350884', 'A handbook of agile software craftsmanship focusing on writing readable and maintainable code.', 2008, 0, now(), now()),
  ('Test Driven Development', '9780321146533', 'By example: a practical guide to test-driven development methodology.', 2002, 0, now(), now()),
  ('Design Patterns', '9780201633610', 'Elements of reusable object-oriented software design patterns.', 1994, 0, now(), now()),
  ('The Pragmatic Programmer', '9780135957059', 'Your journey to mastery in software development.', 2019, 0, now(), now()),
  ('Domain-Driven Design', '9780321125217', 'Tackling complexity in the heart of software through domain modeling.', 2003, 0, now(), now());

-- Book-author associations
INSERT INTO book_authors (book_id, author_id) VALUES
  ((SELECT id FROM books WHERE isbn = '9780134685991'), (SELECT id FROM authors WHERE first_name = 'Joshua' AND last_name = 'Bloch')),
  ((SELECT id FROM books WHERE isbn = '9780321349606'), (SELECT id FROM authors WHERE first_name = 'Brian' AND last_name = 'Goetz')),
  ((SELECT id FROM books WHERE isbn = '9780321349606'), (SELECT id FROM authors WHERE first_name = 'Joshua' AND last_name = 'Bloch')),
  ((SELECT id FROM books WHERE isbn = '9780201485677'), (SELECT id FROM authors WHERE first_name = 'Martin' AND last_name = 'Fowler')),
  ((SELECT id FROM books WHERE isbn = '9780132350884'), (SELECT id FROM authors WHERE first_name = 'Robert' AND last_name = 'Martin')),
  ((SELECT id FROM books WHERE isbn = '9780321146533'), (SELECT id FROM authors WHERE first_name = 'Kent' AND last_name = 'Beck'));
