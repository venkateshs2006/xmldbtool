package com.bnpp;

import com.bnpp.model.FieldMapping;
import com.bnpp.service.XmlSchemaDiscoveryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class IngestionJsonGeneratorTest {

    @Test
    void testGenerateIngestionJsonFromTestXml() throws Exception {
        XmlSchemaDiscoveryService service = new XmlSchemaDiscoveryService();
        File xmlFile = new File("sample-data", "test.xml");
        assertTrue(xmlFile.exists(), "sample-data/test.xml must exist");

        List<FieldMapping> generated = service.generateIngestionJson(xmlFile.getAbsolutePath());
        assertNotNull(generated);
        assertEquals(41, generated.size(), "Generated mappings should have 41 items");

        // Verify countrylist
        FieldMapping m0 = generated.get(0);
        assertEquals("canopyapp/countrylists/countrylist/@id", m0.getXmlField());
        assertEquals("countrylist", m0.getTargetTable());
        assertEquals("id", m0.getTargetColumn());
        assertEquals("PK", m0.getConstraintType());
        assertEquals("TO_INT", m0.getTransform());

        FieldMapping m1 = generated.get(1);
        assertEquals("canopyapp/countrylists/countrylist/@action", m1.getXmlField());
        assertEquals("countrylist", m1.getTargetTable());
        assertEquals("action", m1.getTargetColumn());
        assertEquals("NONE", m1.getConstraintType());
        assertEquals("NONE", m1.getTransform());

        FieldMapping m2 = generated.get(2);
        assertEquals("canopyapp/countrylists/countrylist", m2.getXmlField());
        assertEquals("countrylist", m2.getTargetTable());
        assertEquals("name", m2.getTargetColumn());
        assertEquals("NONE", m2.getConstraintType());
        assertEquals("NONE", m2.getTransform());

        // Verify country
        FieldMapping m3 = generated.get(3);
        assertEquals("canopyapp/countries/country/@id", m3.getXmlField());
        assertEquals("country", m3.getTargetTable());
        assertEquals("id", m3.getTargetColumn());
        assertEquals("PK", m3.getConstraintType());
        assertEquals("TO_INT", m3.getTransform());

        FieldMapping m5 = generated.get(5);
        assertEquals("canopyapp/countries/country/countrylist_id", m5.getXmlField());
        assertEquals("country", m5.getTargetTable());
        assertEquals("countrylist_id", m5.getTargetColumn());
        assertEquals("FK", m5.getConstraintType());
        assertEquals("countrylist.id", m5.getReferences());
        assertEquals("countrylist", m5.getForeignKeyTable());
        assertEquals("id", m5.getForeignKeyColumn());
        assertEquals("TO_INT", m5.getTransform());

        // Verify address parent FK
        FieldMapping mAddrPk = generated.stream()
                .filter(m -> "address".equalsIgnoreCase(m.getTargetTable()) && "id".equalsIgnoreCase(m.getTargetColumn()))
                .findFirst().orElseThrow();
        assertEquals("canopyapp/record/persons/person/addresses/address/@id", mAddrPk.getXmlField());
        assertEquals("PK", mAddrPk.getConstraintType());
        assertEquals("TO_INT", mAddrPk.getTransform());

        FieldMapping mAddrParentFk = generated.stream()
                .filter(m -> "address".equalsIgnoreCase(m.getTargetTable()) && "person_id".equalsIgnoreCase(m.getTargetColumn()))
                .findFirst().orElseThrow();
        assertEquals("canopyapp/record/persons/person/@id", mAddrParentFk.getXmlField());
        assertEquals("address", mAddrParentFk.getTargetTable());
        assertEquals("person_id", mAddrParentFk.getTargetColumn());
        assertEquals("FK", mAddrParentFk.getConstraintType());
        assertEquals("person.id", mAddrParentFk.getReferences());
        assertEquals("person", mAddrParentFk.getForeignKeyTable());
        assertEquals("id", mAddrParentFk.getForeignKeyColumn());
        assertEquals("TO_INT", mAddrParentFk.getTransform());

        // Verify entity cost is TO_DECIMAL
        FieldMapping mCost = generated.stream()
                .filter(m -> "entity".equalsIgnoreCase(m.getTargetTable()) && "cost".equalsIgnoreCase(m.getTargetColumn()))
                .findFirst().orElseThrow();
        assertEquals("canopyapp/record/entities/entity/cost", mCost.getXmlField());
        assertEquals("TO_DECIMAL", mCost.getTransform());
        assertEquals("NONE", mCost.getConstraintType());

        // Verify serialization to JSON
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(generated);
        assertNotNull(json);

        // Deserializing back should preserve references and foreign keys
        List<FieldMapping> reloaded = mapper.readValue(json, new TypeReference<List<FieldMapping>>() {});
        assertEquals(41, reloaded.size());
        FieldMapping reloadedParentFk = reloaded.stream()
                .filter(m -> "address".equalsIgnoreCase(m.getTargetTable()) && "person_id".equalsIgnoreCase(m.getTargetColumn()))
                .findFirst().orElseThrow();
        assertEquals("person.id", reloadedParentFk.getReferences());
        assertEquals("person", reloadedParentFk.getForeignKeyTable());
        assertEquals("id", reloadedParentFk.getForeignKeyColumn());
    }

    @Test
    void testGenerateIngestionJsonFromGenericCompanyXml() throws Exception {
        XmlSchemaDiscoveryService service = new XmlSchemaDiscoveryService();
        File xmlFile = new File("sample-data", "company.xml");
        assertTrue(xmlFile.exists(), "sample-data/company.xml must exist");

        List<FieldMapping> mappings = service.generateIngestionJson(xmlFile.getAbsolutePath());
        assertNotNull(mappings);
        assertEquals(4, mappings.size(), "Should have 4 column mappings for employee");

        FieldMapping mId = mappings.get(0);
        assertEquals("company/employees/employee/id", mId.getXmlField());
        assertEquals("employee", mId.getTargetTable());
        assertEquals("id", mId.getTargetColumn());
        assertEquals("PK", mId.getConstraintType());
        assertEquals("TO_INT", mId.getTransform());

        FieldMapping mName = mappings.get(1);
        assertEquals("company/employees/employee/name", mName.getXmlField());
        assertEquals("employee", mName.getTargetTable());
        assertEquals("name", mName.getTargetColumn());
        assertEquals("NONE", mName.getConstraintType());

        FieldMapping mPos = mappings.get(2);
        assertEquals("company/employees/employee/position", mPos.getXmlField());
        assertEquals("employee", mPos.getTargetTable());
        assertEquals("position", mPos.getTargetColumn());
        assertEquals("NONE", mPos.getConstraintType());

        FieldMapping mDept = mappings.get(3);
        assertEquals("company/employees/employee/department", mDept.getXmlField());
        assertEquals("employee", mDept.getTargetTable());
        assertEquals("department", mDept.getTargetColumn());
        assertEquals("NONE", mDept.getConstraintType());
    }

    @Test
    void testGenerateIngestionJsonFrom138KbUploadedXml() throws Exception {
        XmlSchemaDiscoveryService service = new XmlSchemaDiscoveryService();
        File xmlFile = new File("sample-data", "138-KB-XML-File.xml");
        assertTrue(xmlFile.exists(), "sample-data/138-KB-XML-File.xml must exist");

        List<FieldMapping> mappings = service.generateIngestionJson(xmlFile.getAbsolutePath());
        assertNotNull(mappings);
        assertEquals(4, mappings.size(), "138-KB file should generate 4 mappings for employee table");

        assertEquals("company/employees/employee/id", mappings.get(0).getXmlField());
        assertEquals("employee", mappings.get(0).getTargetTable());
        assertEquals("id", mappings.get(0).getTargetColumn());
        assertEquals("PK", mappings.get(0).getConstraintType());
        assertEquals("TO_INT", mappings.get(0).getTransform());
    }

    @Test
    void testGenerateIngestionJsonFromRawXmlString() throws Exception {
        XmlSchemaDiscoveryService service = new XmlSchemaDiscoveryService();
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <catalog>
                  <products>
                    <product>
                      <product_id>101</product_id>
                      <name>Laptop Pro</name>
                      <price>1299.99</price>
                      <category_id>5</category_id>
                    </product>
                  </products>
                </catalog>
                """;

        List<FieldMapping> mappings = service.generateIngestionJson(xml);
        assertNotNull(mappings);
        assertEquals(4, mappings.size());

        FieldMapping mPk = mappings.stream()
                .filter(m -> "product_id".equalsIgnoreCase(m.getTargetColumn()))
                .findFirst().orElseThrow();
        assertEquals("PK", mPk.getConstraintType());
        assertEquals("TO_INT", mPk.getTransform());

        FieldMapping mFk = mappings.stream()
                .filter(m -> "category_id".equalsIgnoreCase(m.getTargetColumn()))
                .findFirst().orElseThrow();
        assertEquals("FK", mFk.getConstraintType());
        assertEquals("category.id", mFk.getReferences());
        assertEquals("TO_INT", mFk.getTransform());

        FieldMapping mPrice = mappings.stream()
                .filter(m -> "price".equalsIgnoreCase(m.getTargetColumn()))
                .findFirst().orElseThrow();
        assertEquals("TO_DECIMAL", mPrice.getTransform());
    }
}
