CREATE TABLE TERMINAL_BUS_STOP_LINK (
  TERMINAL_ASSET_ID references ASSET(ID),
	BUS_STOP_ASSET_ID references ASSET(ID));

CREATE INDEX TERMINAL_ASSET_ID_idx ON TERMINAL_BUS_STOP_LINK(TERMINAL_ASSET_ID);
CREATE INDEX BUS_STOP_ASSET_ID_idx ON TERMINAL_BUS_STOP_LINK(BUS_STOP_ASSET_ID);

--Add property Liitetty Terminaaliin to Bussipysäkit (massTransitStop)
INSERT INTO LOCALIZED_STRING  (ID,VALUE_FI, CREATED_BY, CREATED_DATE)
VALUES (primary_key_seq.nextval,'Liitetty Terminaaliin','db_migration_v122', sysdate);

INSERT INTO PROPERTY (ID, ASSET_TYPE_ID, PROPERTY_TYPE, REQUIRED, CREATED_BY, PUBLIC_ID, NAME_LOCALIZED_STRING_ID)
VALUES (primary_key_seq.nextval, 10, 'read_only_text', 0, 'db_migration_v122', 'liitetty terminaaliin', (select id from LOCALIZED_STRING where VALUE_FI = 'Liitetty Terminaaliin'));



  
  