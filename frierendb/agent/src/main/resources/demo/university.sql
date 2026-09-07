CREATE TABLE students (sid INT NOT NULL, name TEXT NOT NULL, major TEXT, gpa DOUBLE);
CREATE TABLE courses (cid INT NOT NULL, title TEXT NOT NULL, dept TEXT NOT NULL, credits INT NOT NULL);
CREATE TABLE enrollments (sid INT NOT NULL, cid INT NOT NULL, term TEXT NOT NULL, grade DOUBLE);

INSERT INTO students VALUES
  (1, 'Ada Chen', 'CS', 3.9),
  (2, 'Ben Ortiz', 'CS', 3.4),
  (3, 'Cleo Park', 'Math', 3.7),
  (4, 'Dev Patel', 'CS', 3.1),
  (5, 'Esme Ward', 'Biology', 3.8),
  (6, 'Finn Lowe', 'Math', 2.9),
  (7, 'Gia Rossi', 'Biology', 3.5),
  (8, 'Hugo Kim', 'CS', 2.7),
  (9, 'Iris Novak', 'Art', 3.6),
  (10, 'Jun Sato', 'Art', 3.2),
  (11, 'Kai Brooks', 'CS', NULL),
  (12, 'Lena Fox', 'Math', 3.3),
  (13, 'Milo Reed', 'Biology', 2.8),
  (14, 'Nia Cole', 'CS', 3.95),
  (15, 'Omar Diaz', 'Math', 3.0),
  (16, 'Pia Stone', 'Art', 3.9),
  (17, 'Quin Hale', 'Biology', NULL),
  (18, 'Rae Tran', 'CS', 3.5),
  (19, 'Sol Vega', NULL, 3.3),
  (20, 'Tess Bloom', 'Math', 3.6);

INSERT INTO courses VALUES
  (101, 'Intro to Databases', 'CS', 4),
  (102, 'Algorithms', 'CS', 4),
  (201, 'Linear Algebra', 'Math', 3),
  (202, 'Real Analysis', 'Math', 4),
  (301, 'Genetics', 'Biology', 3),
  (302, 'Cell Biology', 'Biology', 4),
  (401, 'Painting Basics', 'Art', 2),
  (402, 'Art History', 'Art', 3);

INSERT INTO enrollments VALUES
  (1, 101, 'Fall 2025', 4.0), (1, 102, 'Fall 2025', 3.7), (1, 201, 'Spring 2026', 3.5),
  (2, 101, 'Fall 2025', 3.3), (2, 102, 'Spring 2026', 3.0),
  (3, 201, 'Fall 2025', 3.9), (3, 202, 'Spring 2026', 3.7), (3, 101, 'Spring 2026', 3.5),
  (4, 101, 'Fall 2025', 3.0), (4, 102, 'Fall 2025', 2.7),
  (5, 301, 'Fall 2025', 4.0), (5, 302, 'Spring 2026', 3.7),
  (6, 201, 'Fall 2025', 2.7), (6, 202, 'Spring 2026', NULL),
  (7, 301, 'Fall 2025', 3.5), (7, 302, 'Fall 2025', 3.3),
  (8, 101, 'Spring 2026', 2.5), (8, 102, 'Spring 2026', 2.3),
  (9, 401, 'Fall 2025', 3.7), (9, 402, 'Spring 2026', 3.9),
  (10, 401, 'Fall 2025', 3.0), (10, 402, 'Fall 2025', 3.3),
  (11, 101, 'Fall 2025', NULL),
  (12, 201, 'Fall 2025', 3.3), (12, 202, 'Fall 2025', 3.0),
  (13, 301, 'Spring 2026', 2.7),
  (14, 101, 'Fall 2025', 4.0), (14, 102, 'Fall 2025', 4.0), (14, 202, 'Spring 2026', 3.7),
  (15, 201, 'Spring 2026', 3.0),
  (16, 401, 'Fall 2025', 4.0), (16, 402, 'Fall 2025', 3.7),
  (18, 101, 'Spring 2026', 3.5), (18, 102, 'Fall 2025', 3.3),
  (20, 201, 'Fall 2025', 3.7), (20, 202, 'Spring 2026', 3.5);
