-- What a version 1 library holds, frozen as the app wrote it: later schemas must keep all of it.
-- Fictional data (AGENTS.md); android_metadata is the table Android adds to every database.
CREATE TABLE android_metadata (locale TEXT);
INSERT INTO android_metadata VALUES ('pt_BR');

INSERT INTO work VALUES ('work-dom', 'Dom Casmurro', NULL, 'COMICS', NULL);
INSERT INTO work VALUES ('work-sakamoto', 'Sakamoto Days', 'Sakamoto Deizu', 'COMICS', 'MANGA');

INSERT INTO work_version VALUES ('version-dom', 'work-dom', 'local', 'content://tree/Kamayoq', 'Dom Casmurro', 1, 0);
INSERT INTO work_version VALUES ('version-sakamoto', 'work-sakamoto', 'local', 'content://tree/Kamayoq', 'Sakamoto Days', 0, 1);

-- A chapter read to the end, one in the middle, one never opened, one whose file is gone, and a
-- previous edition replaced by the current one at the same path (rule E1).
INSERT INTO chapter VALUES ('chapter-1', 'version-dom', 'Dom Casmurro/001.cbz', 'Capítulo 1', 'CHAPTER', 1.0, 'ARCHIVE', 0, 1, '1024/1760000000000', 'hash-1', 0, NULL);
INSERT INTO chapter VALUES ('chapter-2', 'version-dom', 'Dom Casmurro/002', 'Capítulo 2', 'CHAPTER', 2.5, 'IMAGE_FOLDER', 1, 1, '2048/1760000000000', NULL, 1, NULL);
INSERT INTO chapter VALUES ('chapter-3', 'version-dom', 'Dom Casmurro/003.cbz', 'Volume 1', 'VOLUME', NULL, 'ARCHIVE', 2, 1, '4096/1760000000000', NULL, 0, NULL);
INSERT INTO chapter VALUES ('chapter-gone', 'version-sakamoto', 'Sakamoto Days/170.cbz', 'Capítulo 170', 'CHAPTER', 170.0, 'ARCHIVE', 0, 0, '8192/1760000000000', 'hash-gone', 0, NULL);
INSERT INTO chapter VALUES ('chapter-171', 'version-sakamoto', 'Sakamoto Days/171.cbz', 'Capítulo 171', 'CHAPTER', 171.0, 'ARCHIVE', 1, 1, '9000/1760000000000', 'hash-new', 0, NULL);
INSERT INTO chapter VALUES ('chapter-171-old', 'version-sakamoto', 'Sakamoto Days/171.cbz', 'Capítulo 171', 'CHAPTER', 171.0, 'ARCHIVE', 1, 0, '7000/1750000000000', 'hash-old', 0, 'chapter-171');

INSERT INTO chapter_progress VALUES ('chapter-1', 1761400200000, 'READ_TO_END', 40, 40, 1761400200000, 'device-a', 2);
INSERT INTO chapter_progress VALUES ('chapter-2', NULL, NULL, 12, 30, 1761486600000, 'device-a', 0);
INSERT INTO chapter_progress VALUES ('chapter-gone', 1761400200000, 'PERSON', NULL, NULL, NULL, NULL, 1);
INSERT INTO chapter_progress VALUES ('chapter-171-old', NULL, NULL, 7, 20, 1761000000000, 'device-a', 0);

INSERT INTO work_resume VALUES ('version-dom', 'chapter-2');
INSERT INTO work_resume VALUES ('version-sakamoto', 'chapter-gone');
