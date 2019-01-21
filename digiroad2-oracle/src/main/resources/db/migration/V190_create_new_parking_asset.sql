-- Create new asset
-- Parking asset
INSERT INTO ASSET_TYPE (ID, NAME, GEOMETRY_TYPE, CREATED_BY)
	VALUES (420, 'Pysäköintikielto', 'linear', 'db_migration_v190');
	
-- New LOCALIZED_STRING for the new asset
INSERT INTO LOCALIZED_STRING (ID, VALUE_FI, CREATED_BY, CREATED_DATE)
	VALUES (primary_key_seq.nextval, 'Pysäköintikielto', 'db_migration_v194', sysdate);

INSERT INTO LOCALIZED_STRING (ID, VALUE_FI, CREATED_BY, CREATED_DATE)
	VALUES (primary_key_seq.nextval, 'Voimassaoloaika Pysäköintikielto', 'db_migration_v190', sysdate);

-- New Properties for the new asset	
INSERT INTO PROPERTY (ID, ASSET_TYPE_ID, PROPERTY_TYPE, REQUIRED, CREATED_BY, PUBLIC_ID, NAME_LOCALIZED_STRING_ID)
	VALUES (primary_key_seq.nextval, 420, 'single_choice', 1, 'db_migration_v190', 'road_side_parking', (select max(id) from LOCALIZED_STRING where VALUE_FI = 'Pysäköintikielto'));

INSERT INTO PROPERTY (ID, ASSET_TYPE_ID, PROPERTY_TYPE, REQUIRED, CREATED_BY, PUBLIC_ID, NAME_LOCALIZED_STRING_ID)
	VALUES (primary_key_seq.nextval, 420, 'time_period', 0, 'db_migration_v190', 'parking_validity_period', (select max(id) from LOCALIZED_STRING where VALUE_FI = 'Voimassaoloaika Pysäköintikielto'));

-- Create default values for the field 
INSERT INTO ENUMERATED_VALUE (ID, VALUE, NAME_FI, NAME_SV, CREATED_BY, PROPERTY_ID)
  VALUES (primary_key_seq.nextval, 1, 'Pysähtyminen kielletty', ' ', 'db_migration_v190', (select id from property where public_ID = 'road_side_parking'));

INSERT INTO ENUMERATED_VALUE (ID, VALUE, NAME_FI, NAME_SV, CREATED_BY, PROPERTY_ID)
  VALUES (primary_key_seq.nextval, 2, 'Pysäköinti kielletty', ' ', 'db_migration_v190', (select id from property where public_ID = 'road_side_parking'));

