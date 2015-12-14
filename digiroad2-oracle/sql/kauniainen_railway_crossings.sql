insert into ASSET (ID,ASSET_TYPE_ID,CREATED_BY,MUNICIPALITY_CODE) values (600049,230,'dr2_test_data',235);
insert into LRM_POSITION (ID,MML_ID,START_MEASURE,END_MEASURE,SIDE_CODE) values (70000012, 388553074, 103, 103, 1);
insert into asset_link (ASSET_ID, POSITION_ID) values (600049, 70000012);
insert into single_choice_value(asset_id, enumerated_value_id, property_id) values (600049, (select id from enumerated_value where name_fi='Valo/äänimerkki'), (select id from property where public_id='turvavarustus'));
UPDATE asset
  SET geometry = MDSYS.SDO_GEOMETRY(4401,
                                    3067,
                                    NULL,
                                    MDSYS.SDO_ELEM_INFO_ARRAY(1,1,1),
                                    MDSYS.SDO_ORDINATE_ARRAY(374467, 6677347, 0, 0)
                                   )
  WHERE id = 600049;


insert into ASSET (ID,ASSET_TYPE_ID,CREATED_BY,MUNICIPALITY_CODE) values (600050,230,'dr2_test_data',235);
insert into LRM_POSITION (ID,MML_ID,START_MEASURE,END_MEASURE,SIDE_CODE) values (70000013, 388553074, 103, 103, 1);
insert into asset_link (ASSET_ID, POSITION_ID) values (600050, 70000013);
insert into single_choice_value(asset_id, enumerated_value_id, property_id) values (600050, (select id from enumerated_value where name_fi='Puolipuomi'), (select id from property where public_id='turvavarustus'));
UPDATE asset
  SET geometry = MDSYS.SDO_GEOMETRY(4401,
                                    3067,
                                    NULL,
                                    MDSYS.SDO_ELEM_INFO_ARRAY(1,1,1),
                                    MDSYS.SDO_ORDINATE_ARRAY(374467, 6677347, 0, 0)
                                   )
  WHERE id = 600050;


insert into ASSET (ID,ASSET_TYPE_ID,CREATED_BY,MUNICIPALITY_CODE) values (600051,230,'dr2_test_data',235);
insert into LRM_POSITION (ID,MML_ID,START_MEASURE,END_MEASURE,SIDE_CODE) values (70000014, 388553074, 103, 103, 1);
insert into asset_link (ASSET_ID, POSITION_ID) values (600051, 70000014);
insert into text_property_value(id, asset_id, property_id, value_fi, created_date, created_by) values (600052, 600051, (select id from property where public_id='rautatien_tasoristeyksen_nimi'), 'Hyvä nimi', sysdate, 'dr2_test_data');
insert into single_choice_value(asset_id, enumerated_value_id, property_id) values (600051, (select id from enumerated_value where name_fi='Valo/äänimerkki'), (select id from property where public_id='turvavarustus'));
UPDATE asset
  SET geometry = MDSYS.SDO_GEOMETRY(4401,
                                    3067,
                                    NULL,
                                    MDSYS.SDO_ELEM_INFO_ARRAY(1,1,1),
                                    MDSYS.SDO_ORDINATE_ARRAY(374467, 6677347, 0, 0)
                                   )
  WHERE id = 600051;