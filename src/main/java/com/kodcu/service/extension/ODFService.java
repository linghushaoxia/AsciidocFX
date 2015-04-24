package com.kodcu.service.extension;

import com.kodcu.component.HtmlPane;
import com.kodcu.controller.ApplicationController;
import com.kodcu.other.Constants;
import com.kodcu.other.Current;
import com.kodcu.service.ThreadService;
import com.kodcu.service.ui.IndikatorService;
import netscape.javascript.JSObject;
import org.odftoolkit.odfdom.dom.element.draw.DrawFrameElement;
import org.odftoolkit.odfdom.dom.element.draw.DrawImageElement;
import org.odftoolkit.odfdom.pkg.OdfElement;
import org.odftoolkit.odfdom.type.Color;
import org.odftoolkit.odfdom.type.Length;
import org.odftoolkit.simple.Component;
import org.odftoolkit.simple.TextDocument;
import org.odftoolkit.simple.draw.FrameRectangle;
import org.odftoolkit.simple.draw.FrameStyleHandler;
import org.odftoolkit.simple.draw.Image;
import org.odftoolkit.simple.style.Border;
import org.odftoolkit.simple.style.Font;
import org.odftoolkit.simple.style.ParagraphProperties;
import org.odftoolkit.simple.style.StyleTypeDefinitions.*;
import org.odftoolkit.simple.table.Cell;
import org.odftoolkit.simple.table.CellRange;
import org.odftoolkit.simple.table.Column;
import org.odftoolkit.simple.table.Table;
import org.odftoolkit.simple.text.Paragraph;
import org.odftoolkit.simple.text.ParagraphContainer;
import org.odftoolkit.simple.text.list.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Created by Hakan on 4/13/2015.
 */
@Controller
public class ODFService {

    private final Logger logger = LoggerFactory.getLogger(ODFService.class);

    private final ApplicationController controller;
    private final Current current;
    private final HtmlPane htmlPane;
    private final ThreadService threadService;
    private final IndikatorService indikatorService;

    private TextDocument odtDocument;
    private List<JSObject> unstructuredDocument = new ArrayList<>();
    private Predicate<String> expectedElement = (name) -> Arrays.asList("paragraph", "image", "section", "listing", "colist",
            "table", "quote", "page_break", "olist", "ulist", "admonition", "thematic_break", "sidebar").stream().anyMatch(s -> s.equals(name));

    @Autowired
    public ODFService(final ApplicationController controller, final Current current, final HtmlPane htmlPane,
                      final ThreadService threadService, final IndikatorService indikatorService) {
        this.controller = controller;
        this.current = current;
        this.htmlPane = htmlPane;
        this.threadService = threadService;
        this.indikatorService = indikatorService;
    }

    public void generateODFDocument() {
        threadService.runTaskLater(() -> {
            indikatorService.startCycle();
            try {
                this.openOdtDocument();
                htmlPane.call("convertOdf", current.currentEditorValue());
                this.saveOdtDocument();
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            } finally {
                unstructuredDocument.clear();
                indikatorService.completeCycle();
            }
        });
    }

    private void openOdtDocument() {
        // create an empty text doc to begin
        try {
            odtDocument = TextDocument.newTextDocument();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    private void saveOdtDocument() {
        Path currentTabPath = current.currentPath().get();
        Path currentTabPathDir = currentTabPath.getParent();
        Path odtFilePath = currentTabPathDir.resolve(String.format("%s.odt", currentTabPath.getFileName()));
        try {
            odtDocument.save(odtFilePath.toString());
            threadService.runActionLater(() -> {
                controller.getRecentFilesList().remove(odtFilePath.toString());
                controller.getRecentFilesList().add(0, odtFilePath.toString());
            });
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        } finally {
            odtDocument.close();
        }
    }

    public void buildDocument(String name, JSObject jObj) {
        System.out.println(name);

        if (name.equals("document")) {
            List<AsciiElement> structuredDocument = createNewDocumentStructure();
            buildODTDocument(Optional.ofNullable(structuredDocument));
        } else if (expectedElement.test(name)) {
            unstructuredDocument.add(jObj);
        }
    }

    private List<AsciiElement> createNewDocumentStructure() {
        List<AsciiElement> newTree = new ArrayList<>();

        unstructuredDocument.forEach(item -> {
            String name = getSpecificProperty(item, "name", String.class);
            JSObject blocks = getSpecificProperty(item, "blocks", JSObject.class);
            Integer nOfBlocks = getSpecificProperty(blocks, "length", Integer.class);

            AsciiElement element;
            if (name.equals("section") || name.equals("quote") || name.equals("sidebar") || name.equals("admonition")) {
                List<AsciiElement> subElements = getSectionChildren(newTree, nOfBlocks);
                element = new AsciiElement(name, item, subElements);
            } else {
                element = new AsciiElement(name, item);
            }
            newTree.add(element);
        });
        return newTree;
    }

    private List<AsciiElement> getSectionChildren(List<AsciiElement> newTree, Integer nOfBlocks) {
        List<AsciiElement> subElements = new ArrayList<>();
        int lastIndex = newTree.size() - 1;

        for (int repeat = 0; repeat < nOfBlocks; repeat++) {
            if (newTree.size() == 0) break;
            AsciiElement lastElement = newTree.get(lastIndex);
            subElements.add(lastElement);
            newTree.remove(lastIndex);
            lastIndex -= 1;
        }
        return subElements;
    }

    private void buildODTDocument(Optional<List<AsciiElement>> elements) {
        elements.ifPresent(list -> {
            list.forEach(element -> {
                this.addComponent(element, Paragraph.newParagraph(odtDocument));
            });
        });
    }

    private void buildODTDocument(Optional<List<AsciiElement>> elements, Component component) {
        elements.ifPresent(list -> {
            list.forEach(element -> {
                this.addComponent(element, component);
            });
        });
    }

    private void addComponent(AsciiElement element, Component component) {
        switch (element.getName()) {
            case "paragraph":
                addParagraph(element, component);
                break;
            case "listing":
                addListing(element, component);
                break;
            case "image":
                addImage(element, component);
                break;
            case "section":
                addSection(element);
                break;
            case "colist":
            case "olist":
            case "ulist":
                addList(element, component);
                break;
            case "quote":
                addQuote(element, component);
                break;
            case "table":
                addTable(element);
                break;
            case "page_break":
                addPageBreak(component);
                break;
            case "admonition":
                addAdmonition(element);
                break;
            case "sidebar":
                addSideBlock(element);
                break;
            case "thematic_break":
                removeParagraph(component);
                break;
        }
    }

    private void addSideBlock(AsciiElement element) {
        String title = element.getTitle();
        Table table = odtDocument.addTable(1, 1);

        Cell cell = table.getCellByPosition(0, 0);
        cell.setCellBackgroundColor(new Color("#f8f8f7"));
        Border border = new Border(new Color("#e0e0dc"), 1.0, SupportedLinearMeasure.PT);
        cell.setBorders(CellBordersType.ALL_FOUR, border);

        if (!title.equals("")) {
            Font font = createFont(15, new Color("#7a2518"));
            Paragraph paragraph = cell.addParagraph(title);
            paragraph.setHorizontalAlignment(HorizontalAlignmentType.CENTER);
            paragraph.setFont(font);
        }

        buildODTDocument(element.getChildren(), cell);
    }

    private void removeParagraph(Component component) {
        if (component instanceof Paragraph) {
            Paragraph paragraph = (Paragraph) component;
            paragraph.remove();
        }
    }

    private void addAdmonition(AsciiElement element) {
        String title = element.getTitle();
        if (!title.equals("") && !title.equals("undefined")) {
            Font font = createFont(FontStyle.ITALIC, 12, new Color("#7a2518"));
            Paragraph paragraph = odtDocument.addParagraph(title);
            paragraph.setFont(font);
        }

        Table table = odtDocument.addTable(1, 2);
        Cell rowOColumn0 = table.getCellByPosition(0, 0);
        String caption = getSpecificProperty(element.getjObj(), "caption", String.class);
        rowOColumn0.setDisplayText(caption.toUpperCase());
        rowOColumn0.setHorizontalAlignment(HorizontalAlignmentType.CENTER);
        rowOColumn0.setVerticalAlignment(VerticalAlignmentType.MIDDLE);

        Cell rowOColumn1 = table.getCellByPosition(1, 0);
        if (element.getNOfBlocks() == 0)
            rowOColumn1.setStringValue(element.getContent());
        else {
            buildODTDocument(element.getChildren(), rowOColumn1);
        }

        Column column = table.getColumnByIndex(0);
        column.setUseOptimalWidth(true);
        column.setWidth(32.0);
    }

    private void addPageBreak(Component component) {
        if (component instanceof Paragraph) {
            Paragraph paragraph = (Paragraph) component;
            odtDocument.addPageBreak(paragraph);
        }
    }

    private void addQuote(AsciiElement element, Component component) {
        Cell cell = null;
        ParagraphContainer paragraphContainer;

        if (component instanceof Cell) {
            cell = (Cell) component;
            paragraphContainer = cell;
        } else {
            paragraphContainer = odtDocument;
            Paragraph paragraph = (Paragraph) component;
            paragraph.remove();
        }

        if (element.getNOfBlocks() == 0)
            paragraphContainer.addParagraph(element.getContent());
        else {
            if (Objects.nonNull(cell))
                this.buildODTDocument(element.getChildren(), cell);
            else
                this.buildODTDocument(element.getChildren());
        }

        addQuoteTypes(element, paragraphContainer);
    }

    private void addQuoteTypes(AsciiElement element, ParagraphContainer paragraphContainer) {
        Arrays.asList("attribution", "citetitle").forEach(type -> {
            String attr = getSpecificProperty(element.getAttr(), type, String.class);
            if (!attr.equals("") && !attr.equals("undefined")) {
                Paragraph parag = paragraphContainer.addParagraph("");
                Font font = createFont(FontStyle.ITALIC, 12, Color.BLACK);
                parag.setFont(font);
                if (type.startsWith("attr"))
                    parag.setTextContent("—".concat(attr));
                else
                    parag.setTextContent(attr);
            }
        });
    }

    private void addParagraph(AsciiElement element, Component component) {
        Paragraph paragraph;
        if (component instanceof Cell) {
            Cell cell = (Cell) component;
            paragraph = cell.addParagraph(element.getContent());
            ParagraphProperties properties = paragraph.getStyleHandler().getParagraphPropertiesForRead();
            properties.setMarginLeft(1.0);
            properties.setMarginRight(1.0);
        } else {
            paragraph = odtDocument.addParagraph(element.getContent());
        }
        paragraph.setHorizontalAlignment(HorizontalAlignmentType.JUSTIFY);
    }

    private void addList(AsciiElement element, Component component) {
        ListContainer listContainer;
        ParagraphContainer paragraphContainer;
        if (component instanceof Cell) {
            Cell cell = (Cell) component;
            listContainer = cell;
            paragraphContainer = cell;
        } else {
            listContainer = odtDocument;
            paragraphContainer = odtDocument;
        }

        String title = element.getTitle();
        if (!title.equals("") && !title.equals("undefined")) {
            Font font = createFont(FontStyle.ITALIC, 12, new Color("#7a2518"));
            Paragraph paragraph = paragraphContainer.addParagraph(title);
            paragraph.setFont(font);
        }

        addListItems(element, listContainer);
    }

    private void addListItems(AsciiElement element, ListContainer listContainer) {
        int len = element.getItemsLength();
        if (len > 0) {
            org.odftoolkit.simple.text.list.List list = listContainer.addList();
            ListDecorator dec = findDecorator(element.getName());
            list.setDecorator(dec);
            for (int inc = 0; inc < len; inc++) {
                JSObject blocks = getSpecificProperty(element.getItemByIndex(inc), "blocks", JSObject.class);
                String text = getSpecificProperty(element.getItemByIndex(inc), "text", String.class);
                ListItem listItem = list.addItem(text);
                addSubList(listItem, blocks);
            }
        }
    }

    private ListDecorator findDecorator(String name) {
        ListDecorator dec;
        if (name.equals("olist")) {
            dec = new NumberDecorator(odtDocument);
        } else {
            dec = new BulletDecorator(odtDocument);
        }
        return dec;
    }

    private void addSubList(ListItem listItem, JSObject blocks) {
        int nOfBlocks = getSpecificProperty(blocks, "length", Integer.class);
        if (nOfBlocks > 0) {
            for (int counter = 0; counter < nOfBlocks; counter++) {
                JSObject subList = getSpecificProperty(blocks, counter, JSObject.class);
                JSObject subListItems = getSpecificProperty(subList, "blocks", JSObject.class);
                int itemsLength = getSpecificProperty(subListItems, "length", Integer.class);
                if (itemsLength > 0) {
                    org.odftoolkit.simple.text.list.List list = listItem.addList();
                    for (int itemIndex = 0; itemIndex < itemsLength; itemIndex++) {
                        JSObject subListItem = getSpecificProperty(subListItems, itemIndex, JSObject.class);
                        String context = getSpecificProperty(subListItem, "context", String.class);
                        String itemText = getSpecificProperty(subListItem, "text", String.class);
                        ListDecorator decorator = findDecorator(context);
                        list.setDecorator(decorator);
                        ListItem listContainer = list.addItem(itemText);
                        JSObject nestedSubListItemBlocks = getSpecificProperty(subListItem, "blocks", JSObject.class);
                        addSubList(listContainer, nestedSubListItemBlocks);
                    }
                }
            }
        }
    }

    private void addListing(AsciiElement element, Component component) {
        if (component instanceof Paragraph) {
            if (element.getTitle().equals("")) {
                Table table = odtDocument.addTable(1, 1);
                Cell cell = table.getCellByPosition(0, 0);
                cell.setStringValue(element.getContent());
            } else {
                Table table = odtDocument.addTable(2, 1);
                Cell cell = table.getCellByPosition(0, 0);
                cell.setStringValue(element.getTitle());
                cell.setHorizontalAlignment(HorizontalAlignmentType.CENTER);
                Cell cell2 = table.getCellByPosition(0, 1);
                cell2.setStringValue(element.getContent());
            }
        } else if (component instanceof Cell) {
            Cell cell = (Cell) component;
            if (!element.getTitle().equals(""))
                cell.addParagraph(element.getTitle());
            cell.addParagraph(element.getContent());
        }
    }

    private void addSection(AsciiElement element) {
        removeFirstEmptyParagraphs(odtDocument);
        Font font = createFont(12, new Color("#ba3925"));

        switch (element.getLevel()) {
            case 1:
                font.setSize(18);
                break;
            case 2:
                font.setSize(17);
                break;
            case 3:
                font.setSize(16);
                break;
            case 4:
                font.setSize(15);
                break;
            case 5:
                font.setSize(14);
                break;
            case 6:
                font.setSize(13);
                break;
        }

        Paragraph paragraph = odtDocument.addParagraph(element.getTitle());
        paragraph.setFont(font);
        this.buildODTDocument(element.getChildren());
    }

    private void removeFirstEmptyParagraphs(ParagraphContainer paragraphContainer) {
        // only if the initial doc containing less than 4 elems
        int paragraphLength = paragraphContainer.getParagraphContainerElement().getLength();
        if (paragraphLength < 4) {
            Iterator<Paragraph> params = paragraphContainer.getParagraphIterator();
            while (params.hasNext()) {
                Paragraph param = params.next();
                param.remove();
            }
        }
    }

    private void addImage(AsciiElement element, Component component) {
        String imageUrl = getSpecificProperty(element.getAttr(), "target", String.class);

        if (Constants.IMAGE_URL_MATCH.matcher(imageUrl).matches()) {
            if (component instanceof Paragraph) {
                Paragraph paragraph = (Paragraph) component;
                paragraph.remove();
            }
            return;
        }

        Path currentTabPath = current.currentPath().get();
        Path currentTabPathDir = currentTabPath.getParent();
        Path path = currentTabPathDir.resolve(imageUrl);

        Paragraph para = odtDocument.addParagraph("");
        para.setHorizontalAlignment(HorizontalAlignmentType.CENTER);
        Image image = Image.newImage(para, path.toUri());

        FrameStyleHandler handler = image.getStyleHandler();
        handler.setAchorType(AnchorType.AS_CHARACTER);

        setImageSize(image);
        setElementTitle(element, "Figure", odtDocument);
    }

    private void setImageSize(Image image) {
        DrawImageElement diElem = image.getOdfElement();
        DrawFrameElement dfElem = (DrawFrameElement) diElem.getParentNode();
        FrameRectangle rect = image.getRectangle();
        if (rect.getWidth() > 14.0) {
            dfElem.setSvgWidthAttribute(Length.mapToUnit(String.valueOf(400) + "px", Length.Unit.CENTIMETER));
        }
        if (rect.getHeight() > 14.0) {
            dfElem.setSvgHeightAttribute(Length.mapToUnit(String.valueOf(300) + "px", Length.Unit.CENTIMETER));
        }
    }

    private void setElementTitle(AsciiElement element, String prefix, ParagraphContainer paragraphContainer) {
        String title = element.getTitle();
        if (!title.equals("") && !title.equals("undefined")) {
            paragraphContainer.addParagraph("");
            Font font = createFont(FontStyle.ITALIC, 12, new Color("#7a2518"));
            Paragraph titleParam = paragraphContainer.addParagraph(String.join(" : ", prefix, title));
            titleParam.setHorizontalAlignment(HorizontalAlignmentType.CENTER);
            titleParam.setFont(font);
        }
    }

    private void addTable(AsciiElement element) {
        Table table = odtDocument.addTable(element.getRowsLength(), element.getColumnsLength());
        boolean headExist = false;

        for (String selection : Arrays.asList("head", "body", "foot")) {
            int rowSelection = element.getRowLengthBySelection(selection);
            int rowTable = 0;

            if (selection.equals("head")) {
                if (rowSelection == 1)
                    headExist = true;
            } else if (selection.equals("body")) {
                if (rowSelection != 0 && headExist) {
                    rowTable = 1;
                    rowSelection += rowTable;
                }
            } else {
                if (rowSelection != 0) {
                    rowTable = element.getRowsLength() - rowSelection;
                    rowSelection = element.getRowsLength();
                }
            }
            traverseCells(element, table, selection, rowSelection, rowTable);
        }
        setElementTitle(element, "Table", odtDocument);
    }

    private void traverseCells(AsciiElement element, Table table, String selection, int rowSelection, int rowTable) {
        for (int rowTableIndex = rowTable, rowElement = 0; rowTableIndex < rowSelection; rowTableIndex++, rowElement++) {
            int cellColumns = element.getNOfColumn(selection, rowElement);
            for (int column = 0; column < cellColumns; column++) {
                editCell(element, table, selection, rowTableIndex, rowElement, column);
            }
        }
    }

    private void editCell(AsciiElement element, Table table, String selection, int rowTableIndex, int rowElement, int column) {
        JSObject documentCell = element.getCell(selection, rowElement, column);
        Map<String, String> attrs = getCellAttributes(documentCell);
        String cellText = getSpecificProperty(documentCell, "text", String.class);

        Cell tableCell = getCell(table, rowTableIndex, column, attrs);

        setAlignmentTypes(attrs, tableCell);
        setSpecificFontStyle(selection, tableCell, attrs);
        setBorderRight(tableCell);
        tableCell.setStringValue(cellText);
    }

    private Cell getCell(Table table, int rowTableIndex, int column, Map<String, String> attrs) {
        Cell tableCell = table.getCellByPosition(column, rowTableIndex);
        OdfElement container = tableCell.getFrameContainerElement();
        String localName = container.getOdfName().getLocalName();

        if (localName.equals("table-cell")) {
            tableCell = setSpanAttributeOfCell(table, attrs, tableCell, container);
        } else if (localName.equals("covered-table-cell")) {
            tableCell = getCell(table, rowTableIndex, column + 1, attrs);
        }

        return tableCell;
    }

    private Cell setSpanAttributeOfCell(Table table, Map<String, String> attrs, Cell tableCell, OdfElement container) {
        int colSpan = Integer.valueOf(attrs.get("colspan"));
        int rowspan = Integer.valueOf(attrs.get("rowspan"));
        int row = tableCell.getRowIndex();
        int column = tableCell.getColumnIndex();

        boolean hasOfficeValueType = container.hasAttribute("office:value-type");
        if (!hasOfficeValueType) {
            if (colSpan != rowspan) {
                if (colSpan == table.getColumnCount())
                    colSpan -= 1;
                if (rowspan == table.getRowCount())
                    rowspan -= 1;
                if (colSpan == 1)
                    colSpan = column;
                if (rowspan == 1)
                    rowspan = row;
                // spanned attribute in action
                CellRange cellRange = table.getCellRangeByPosition(column, row, colSpan, rowspan);
                cellRange.merge();
            }
        } else {
            tableCell = getCell(table, row, column + 1, attrs);
        }
        return tableCell;
    }

    private void setAlignmentTypes(Map<String, String> attrs, Cell tableCell) {
        HorizontalAlignmentType hAlign = HorizontalAlignmentType.enumValueOf(attrs.get("halign"));
        VerticalAlignmentType vAlign = VerticalAlignmentType.enumValueOf(attrs.get("valign"));
        tableCell.setHorizontalAlignment(hAlign);
        tableCell.setVerticalAlignment(vAlign);
    }

    private void setSpecificFontStyle(String selection, Cell tableCell, Map<String, String> attrs) {
        Font font = createFont(12, Color.BLACK);
        if (selection.equals("head")) {
            font.setFontStyle(FontStyle.BOLDITALIC);
        } else if (selection.equals("foot")) {
            font.setFontStyle(FontStyle.ITALIC);
        } else {
            if (attrs.containsKey("style")) {
                String style = attrs.get("style");
                if (style.equals("strong"))
                    font.setFontStyle(FontStyle.BOLD);
                else if (style.equals("emphasis"))
                    font.setFontStyle(FontStyle.ITALIC);
                else if (style.equals("header"))
                    font.setFontStyle(FontStyle.BOLDITALIC);
            }
        }
        tableCell.setFont(font);
    }

    private void setBorderRight(Cell tableCell) {
        Border borderRight = new Border(Color.BLACK, 1.0, SupportedLinearMeasure.PT);
        tableCell.setBorders(CellBordersType.RIGHT, borderRight);
    }

    private Map<String, String> getCellAttributes(JSObject cell) {
        Map<String, String> attrList = new LinkedHashMap<>();
        String documentStr = cell.toString();

        for (String regex : Arrays.asList("\"(\\w+)\"(?:=>)\"?(\\w+)\"?", "(colspan)(?:[\\:\\s]+)(\\d+)", "(rowspan)(?:[\\:\\s]+)(\\d+)")) {
            Pattern pattern = Pattern.compile(regex);
            Matcher mather = pattern.matcher(documentStr);
            while (mather.find()) {
                attrList.put(mather.group(1), mather.group(2));
            }
        }
        return attrList;
    }

    private <T> T getSpecificProperty(JSObject from, String propertyName, Class<T> returnType) {
        return returnType.cast(from.getMember(propertyName));
    }

    private <T> T getSpecificProperty(JSObject from, int index, Class<T> returnType) {
        return returnType.cast(from.getSlot(index));
    }

    private Font createFont(int fontSize, Color color) {
        return new Font("Times New Roman", FontStyle.REGULAR, fontSize, color);
    }

    private Font createFont(FontStyle style, int fontSize, Color color) {
        return new Font("Times New Roman", style, fontSize, color);
    }

    private class AsciiElement {
        private final String name;
        private final JSObject jObj;
        private final Optional<List<AsciiElement>> children;

        AsciiElement(final String name, final JSObject jObj) {
            this(name, jObj, null);
        }

        AsciiElement(final String name, final JSObject jObj, final List<AsciiElement> children) {
            this.name = name;
            this.jObj = jObj;
            if (Objects.nonNull(children))
                Collections.reverse(children);
            this.children = Optional.ofNullable(children);
        }

        JSObject getjObj() {
            return jObj;
        }

        String getName() {
            return name;
        }

        Optional<List<AsciiElement>> getChildren() {
            return children;
        }

        int getLevel() {
            return (Integer) jObj.getMember("level");
        }

        JSObject getBlocks() {
            return (JSObject) jObj.getMember("blocks");
        }

        JSObject getBlockByIndex(int index) {
            return (JSObject) getBlocks().getSlot(index);
        }

        int getNOfBlocks() {
            return (Integer) getBlocks().getMember("length");
        }

        String getTitle() {
            return jObj.getMember("title").toString();
        }

        String getContent() {
            return jObj.getMember("content").toString();
        }

        JSObject getAttr() {
            return (JSObject) jObj.getMember("attr");
        }

        JSObject getItems() {
            return (JSObject) jObj.getMember("items");
        }

        JSObject getItemByIndex(int index) {
            return (JSObject) ((JSObject) jObj.getMember("items")).getSlot(index);
        }

        int getItemsLength() {
            return (Integer) getItems().getMember("length");
        }

        JSObject getRows() {
            return (JSObject) jObj.getMember("rows");
        }

        JSObject getColumns() {
            return (JSObject) jObj.getMember("columns");
        }

        int getColumnsLength() {
            return (Integer) getColumns().getMember("length");
        }

        JSObject getTableBody() {
            return (JSObject) getRows().getMember("body");
        }

        int getTableBodyLength() {
            return (Integer) getTableBody().getMember("length");
        }

        JSObject getTableHead() {
            return (JSObject) getRows().getMember("head");
        }

        int getTableHeadLength() {
            return (Integer) getTableHead().getMember("length");
        }

        JSObject getTableFoot() {
            return (JSObject) getRows().getMember("foot");
        }

        int getTableFootLength() {
            return (Integer) getTableFoot().getMember("length");
        }

        int getRowsLength() {
            return getTableBodyLength() + getTableFootLength() + getTableHeadLength();
        }

        JSObject getTableSectionByName(String name) {
            return (JSObject) getRows().getMember(name);
        }

        int getRowLengthBySelection(String name) {
            return (Integer) getTableSectionByName(name).getMember("length");
        }

        int getNOfColumn(String selection, int row) {
            return (Integer) ((JSObject) getTableSectionByName(selection).getSlot(row)).getMember("length");
        }

        JSObject getCell(String selection, int row, int column) {
            return (JSObject) ((JSObject) getTableSectionByName(selection).getSlot(row)).getSlot(column);
        }

        @Override
        public String toString() {
            return "AsciiElement{" +
                    "children=" + children +
                    ", name='" + name + '\'' +
                    ", jObj=" + jObj +
                    '}';
        }
    }
}
