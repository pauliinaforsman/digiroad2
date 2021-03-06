ALTER TABLE MANOEUVRE
  ADD CREATED_BY VARCHAR2(128)
  ADD CREATED_DATE TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL;

-- Change column properties to acept null
ALTER TABLE MANOEUVRE MODIFY (MODIFIED_DATE default (null));
ALTER TABLE MANOEUVRE MODIFY (MODIFIED_DATE TIMESTAMP null);


--Copy all modified user info to create column
UPDATE MANOEUVRE
SET CREATED_BY = MODIFIED_BY
, CREATED_DATE = MODIFIED_DATE;