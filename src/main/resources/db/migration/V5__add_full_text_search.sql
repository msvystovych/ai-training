-- Add tsvector column for full-text search
ALTER TABLE books ADD COLUMN search_vector tsvector;

-- Function to build search vector from book title + description
CREATE OR REPLACE FUNCTION books_search_vector_update() RETURNS trigger AS $$
BEGIN
  NEW.search_vector :=
    setweight(to_tsvector('english', COALESCE(NEW.title, '')), 'A') ||
    setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'C');
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger on book insert/update
CREATE TRIGGER trg_books_search_vector
  BEFORE INSERT OR UPDATE OF title, description ON books
  FOR EACH ROW EXECUTE FUNCTION books_search_vector_update();

-- GIN index for fast full-text lookups
CREATE INDEX idx_books_search_vector ON books USING GIN (search_vector);

-- Backfill search_vector for any existing rows
UPDATE books SET search_vector =
  setweight(to_tsvector('english', COALESCE(title, '')), 'A') ||
  setweight(to_tsvector('english', COALESCE(description, '')), 'C');
