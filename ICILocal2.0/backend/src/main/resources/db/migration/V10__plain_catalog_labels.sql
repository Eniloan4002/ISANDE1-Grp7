-- Presentation-only catalog cleanup. Preserve institution-customized names.
UPDATE campuses SET name='Bugo, Cagayan de Oro'
WHERE code='BUGO' AND name='Bugo ' || chr(8212) || ' Cagayan de Oro';

UPDATE programs SET name=replace(name,' ' || chr(8212) || ' ',': ')
WHERE (code='TVL_COOKERY' AND name='TVL ' || chr(8212) || ' Cookery')
   OR (code='TVL_ICT' AND name='TVL ' || chr(8212) || ' Information and Communications Technology');
