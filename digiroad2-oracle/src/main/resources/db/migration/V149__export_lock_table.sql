CREATE TABLE EXPORT_LOCK (id NUMERIC NOT NULL PRIMARY KEY, description VARCHAR(4000));
ALTER TABLE EXPORT_LOCK ADD CONSTRAINT CK_EXPORT_ID CHECK (id = 1);