package vn.cxn.graph.indexer.delphi;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DelphiIndexerTest {

    @Test
    public void testAnalyzeWithRegex_TQueryAndMultilineSql() {
        DelphiIndexer indexer = new DelphiIndexer();
        String content = "unit TForm1;\n" +
                "interface\n" +
                "implementation\n" +
                "\n" +
                "procedure TForm1.LoadData;\n" +
                "begin\n" +
                "  Query1.SQL.Clear;\n" +
                "  Query1.SQL.Add('SELECT id, name');\n" +
                "  Query1.SQL.Add('FROM customers');\n" +
                "  Query1.SQL.Add('WHERE active = 1');\n" +
                "  Query1.Open;\n" +
                "end;\n" +
                "\n" +
                "procedure TForm1.SaveData;\n" +
                "begin\n" +
                "  Query2.SQL.Text := 'SELECT * ' +\n" +
                "                     'FROM orders ' +\n" +
                "                     'WHERE status = ''pending''';\n" +
                "  Query2.ExecSQL;\n" +
                "end;\n" +
                "\n" +
                "end.";

        DelphiIndexer.DelphiMetadata metadata = indexer.analyzeWithRegex(content);
        assertNotNull(metadata);
        assertEquals("TForm1", metadata.unitName);

        // Kiểm tra TForm1.LoadData
        assertTrue(metadata.methodsMap.containsKey("TForm1.LoadData"));
        DelphiIndexer.DelphiMethodInfo loadDataInfo = metadata.methodsMap.get("TForm1.LoadData");
        assertEquals(1, loadDataInfo.queries.size());
        DelphiIndexer.SqlQueryInfo query1 = loadDataInfo.queries.get(0);
        assertTrue(query1.sql.contains("SELECT id, name FROM customers WHERE active = 1"));
        assertTrue(query1.tables.contains("customers"));

        // Kiểm tra TForm1.SaveData
        assertTrue(metadata.methodsMap.containsKey("TForm1.SaveData"));
        DelphiIndexer.DelphiMethodInfo saveDataInfo = metadata.methodsMap.get("TForm1.SaveData");
        assertEquals(1, saveDataInfo.queries.size());
        DelphiIndexer.SqlQueryInfo query2 = saveDataInfo.queries.get(0);
        assertTrue(query2.sql.contains("SELECT * FROM orders WHERE status = 'pending'"));
        assertTrue(query2.tables.contains("orders"));
    }

    @Test
    public void testAnalyzeWithRegex_ComplexWithBlockAndConditionalSql() {
        DelphiIndexer indexer = new DelphiIndexer();
        String content = "unit ArticleType1;\n" +
                "interface\n" +
                "implementation\n" +
                "\n" +
                "procedure TArticleType.Button1Click(Sender: TObject);\n" +
                "begin\n" +
                "with XXZL do\n" +
                "  begin\n" +
                "    active:=false;\n" +
                "    sql.Clear;\n" +
                "    sql.add('select XXZL2.XieXing,XXZL2.XieMing,XXZL2.KHDH,');\n" +
                "    sql.Add('case when GXC.GXLB is not null then ' + '''' + 'Yes' + '''' + ' else ' + '''' + '0' + '''' + ' end as C, ');\n" +
                "    sql.add('case when GXS.GXLB is not null then ' + '''' + 'Yes' + '''' + ' else ' + '''' + '0' + '''' + ' end as S,');\n" +
                "    sql.add('case when GXA.GXLB is not null then ' + '''' + 'Yes' + '''' + ' else ' + '''' + '0' + '''' + ' end as A,');\n" +
                "    sql.add('case when GXO.GXLB is not null then ' + '''' + 'Yes' + '''' + ' else ' + '''' + '0' + '''' + ' end as O,');\n" +
                "    sql.add('case when GXW.GXLB is not null then ' + '''' + 'Yes' + '''' + ' else ' + '''' + '0' + '''' + ' end as W,');\n" +
                "    sql.add('case when GXI.GXLB is not null then ' + '''' + 'Yes' + '''' + ' else ' + '''' + '0' + '''' + ' end as I,');\n" +
                "    sql.add('case when GXY.GXLB is not null then ' + '''' + 'Yes' + '''' + ' else ' + '''' + '0' + '''' + ' end as Y,');\n" +
                "    sql.add('case when GXZ.GXLB is not null then ' + '''' + 'Yes' + '''' + ' else ' + '''' + '0' + '''' + ' end as Z');\n" +
                "    sql.add('from (select XXZL.XieXing,max(XXZL.XieMing) as XieMing,max(XXZL.KHDH) as KHDH from XXZL ');\n" +
                "    sql.add('where XXZL.XieXing like ' + '''' + edit1.Text + '%' + '''');\n" +
                "    sql.add('and XXZL.XieMing like ' + '''' + edit5.text + '%' + '''');\n" +
                "    sql.add('and KHDH like' + '''' + edit4.Text + '%' + '''');\n" +
                "    if checkBox1.Checked then\n" +
                "      begin\n" +
                "        sql.add('and exists (select DDZL.DDBH from DDZL ');\n" +
                "        sql.add('left join ZLZL on ZLZL.ZLBH=DDZL.ZLBH ');\n" +
                "        sql.add('where DDZL.XieXing=XXZL.XieXing and DDZL.SheHao=XXZL.SheHao');\n" +
                "        sql.add('and ZLZL.CQDH=' + '''' + 'LTY' + '''')  ;\n" +
                "        sql.add(' and DDZL.ShipDate>=(getdate()-31))');\n" +
                "      end;\n" +
                "    sql.add(' group by XXZL.XieXing) XXZL2');\n" +
                "    sql.add('left join (select XXGX.XieXing,XXGX.GXLB from XXGX where XXGX.GXLB=' + '''' + 'C' + '''' + ') GXC');\n" +
                "    sql.add('             on GXC.XieXing=XXZL2.XieXing');\n" +
                "    sql.add('left join (select XXGX.XieXing,XXGX.GXLB from XXGX where XXGX.GXLB=' + '''' + 'S' + '''' + ') GXS');\n" +
                "    sql.add('             on GXS.XieXing=XXZL2.XieXing');\n" +
                "    sql.add('left join (select XXGX.XieXing,XXGX.GXLB from XXGX where XXGX.GXLB=' + '''' + 'A' + '''' + ') GXA');\n" +
                "    sql.add('             on GXA.XieXing=XXZL2.XieXing');\n" +
                "    sql.add('left join (select XXGX.XieXing,XXGX.GXLB from XXGX where XXGX.GXLB=' + '''' + 'O' + '''' + ') GXO');\n" +
                "    sql.add('             on GXO.XieXing=XXZL2.XieXing');\n" +
                "    sql.add('left join (select XXGX.XieXing,XXGX.GXLB from XXGX where XXGX.GXLB=' + '''' + 'W' + '''' + ') GXW');\n" +
                "    sql.Add('             on GXW.XieXing=XXZL2.XieXing');\n" +
                "    sql.add('left join (select XXGX.XieXing,XXGX.GXLB from XXGX where XXGX.GXLB=' + '''' + 'I' + '''' + ') GXI');\n" +
                "    sql.add('             on GXI.XieXing=XXZL2.XieXing');\n" +
                "    sql.add('left join (select XXGX.XieXing,XXGX.GXLB from XXGX where XXGX.GXLB=' + '''' + 'Y' + '''' + ') GXY');\n" +
                "    sql.add('             on GXY.XieXing=XXZL2.XieXing');\n" +
                "    sql.add('left join (select XXGX.XieXing,XXGX.GXLB from XXGX where XXGX.GXLB=' + '''' + 'Z' + '''' + ') GXZ');\n" +
                "    sql.add('             on GXZ.XieXing=XXZL2.XieXing');\n" +
                "    sql.add('order by XXZL2.XieXing');\n" +
                "    Active:=true;\n" +
                "  end;\n" +
                "BB4.Enabled:=true;\n" +
                "end;\n" +
                "\n" +
                "end.";

        DelphiIndexer.DelphiMetadata metadata = indexer.analyzeWithRegex(content);
        assertNotNull(metadata);
        assertEquals("ArticleType1", metadata.unitName);

        assertTrue(metadata.methodsMap.containsKey("TArticleType.Button1Click"));
        DelphiIndexer.DelphiMethodInfo methodInfo = metadata.methodsMap.get("TArticleType.Button1Click");
        assertEquals(1, methodInfo.queries.size());

        DelphiIndexer.SqlQueryInfo query = methodInfo.queries.get(0);
        
        // Xác minh xem tất cả các Table cần thiết có được trích xuất chính xác không
        assertTrue(query.tables.contains("XXZL"), "Nên chứa bảng XXZL");
        assertTrue(query.tables.contains("DDZL"), "Nên chứa bảng DDZL");
        assertTrue(query.tables.contains("ZLZL"), "Nên chứa bảng ZLZL");
        assertTrue(query.tables.contains("XXGX"), "Nên chứa bảng XXGX");
    }
}
