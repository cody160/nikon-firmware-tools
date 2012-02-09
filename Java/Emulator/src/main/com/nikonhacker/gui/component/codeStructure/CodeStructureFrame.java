package com.nikonhacker.gui.component.codeStructure;

import com.mxgraph.canvas.mxICanvas;
import com.mxgraph.canvas.mxSvgCanvas;
import com.mxgraph.model.mxCell;
import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.util.mxCellRenderer;
import com.mxgraph.util.mxConstants;
import com.mxgraph.util.mxRectangle;
import com.mxgraph.util.mxUtils;
import com.mxgraph.util.png.mxPngEncodeParam;
import com.mxgraph.util.png.mxPngImageEncoder;
import com.nikonhacker.Format;
import com.nikonhacker.dfr.*;
import com.nikonhacker.gui.EmulatorUI;
import com.nikonhacker.gui.component.DocumentFrame;
import com.nikonhacker.gui.component.PrintWriterArea;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.List;

public class CodeStructureFrame extends DocumentFrame
{
    private static final int FRAME_WIDTH = 800;
    private static final int FRAME_HEIGHT = 600;
    private static final int FUNCTION_CELL_WIDTH = 100;
    private static final int FUNCTION_CELL_HEIGHT = 30;
    private static final int FAKE_FUNCTION_CELL_WIDTH = 50;
    private static final int FAKE_FUNCTION_CELL_HEIGHT = 20;

    Object parent;
    CodeStructureMxGraph graph;
    CodeStructure codeStructure;
    // Map from value object (Function or anonymous Object when calling unknown destination) to cell
    Map<Object,Object> cellObjects = new HashMap<Object, Object>();
    Set<Jump> renderedCalls = new HashSet<Jump>();
    private final PrintWriterArea listingArea;
    private mxGraphComponent graphComponent;

    public enum Orientation{
        HORIZONTAL(SwingConstants.WEST),
        VERTICAL(SwingConstants.NORTH);
        
        private int swingValue;

        Orientation(int swingValue) {
            this.swingValue = swingValue;
        }

        public int getSwingValue() {
            return swingValue;
        }
    }

    public CodeStructureFrame(String title, boolean resizable, boolean closable, boolean maximizable, boolean iconifiable, final CodeStructure codeStructure, final EmulatorUI ui) {
        super(title, resizable, closable, maximizable, iconifiable, ui);
        setSize(FRAME_WIDTH, FRAME_HEIGHT);
        this.codeStructure = codeStructure;

        // Create fake structure
        // createFakeStructure();

        // Create toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        final JComboBox orientationCombo = new JComboBox(new Orientation[]{Orientation.HORIZONTAL, Orientation.VERTICAL});
        Orientation currentOrientation = getCurrentOrientation();
        orientationCombo.setSelectedItem(currentOrientation);
        orientationCombo.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Orientation selOrientation = (Orientation) orientationCombo.getSelectedItem();
                ui.getPrefs().setCodeStructureGraphOrientation(selOrientation.name());
                graph.setOrientation(selOrientation.getSwingValue());
            }
        });
        toolbar.add(orientationCombo);
        
        final JTextField targetAddressField = new JTextField(7);
        toolbar.add(targetAddressField);
        
        JButton exploreButton = new JButton("Explore");
        exploreButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                try {
                    int address = Format.parseUnsigned(targetAddressField.getText());
                    Function function = codeStructure.getFunctions().get(address);
                    if (function == null) {
                        JOptionPane.showMessageDialog(CodeStructureFrame.this, "No start of function found at address 0x" + Format.asHex(address, 8), "Cannot explore function", JOptionPane.ERROR_MESSAGE);
                    }
                    else{
                        graph.expandFunction(function, CodeStructureFrame.this);
                    }
                } catch (ParsingException ex) {
                    JOptionPane.showMessageDialog(CodeStructureFrame.this, ex.getMessage(), "Error parsing address", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        toolbar.add(exploreButton);

        JButton svgButton = new JButton("Save as SVG");
        svgButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final JFileChooser fc = new JFileChooser();

                fc.setDialogTitle("Save SVG as...");
                fc.setCurrentDirectory(new java.io.File("."));
                fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
                fc.setAcceptAllFileFilterUsed(true);

                if (fc.showOpenDialog(CodeStructureFrame.this) == JFileChooser.APPROVE_OPTION) {
                    try {
                        saveSvg(fc.getSelectedFile());
                    } catch (IOException e1) {
                        JOptionPane.showMessageDialog(CodeStructureFrame.this, e1.getMessage(), "Error saving to SVG", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        });
        toolbar.add(svgButton);

        JButton pngButton = new JButton("Save as PNG");
        pngButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final JFileChooser fc = new JFileChooser();

                fc.setDialogTitle("Save PNG as...");
                fc.setCurrentDirectory(new java.io.File("."));
                fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
                fc.setAcceptAllFileFilterUsed(true);

                if (fc.showOpenDialog(CodeStructureFrame.this) == JFileChooser.APPROVE_OPTION) {
                    try {
                        savePng(fc.getSelectedFile());
                    } catch (IOException e1) {
                        JOptionPane.showMessageDialog(CodeStructureFrame.this, e1.getMessage(), "Error saving to SVG", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        });
        toolbar.add(pngButton);

        JButton clearButton = new JButton("Clear");
        clearButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                graph.removeCells(graph.getChildCells(graph.getDefaultParent(), true, true));
                cellObjects = new HashMap<Object, Object>();
                renderedCalls = new HashSet<Jump>();
                graph.executeLayout();
            }
        });
        toolbar.add(clearButton);

        
        // Create left hand graph
        graph = new CodeStructureMxGraph(getCurrentOrientation().getSwingValue());
        Component graphComponent = getGraphPane();

        // Create right hand listing
        listingArea = new PrintWriterArea(50, 80);
        listingArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        Component listingComponent = getListingPane();

        // Create a left-right split pane
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, graphComponent, listingComponent);

        // Create and fill main panel
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(toolbar, BorderLayout.NORTH);
        mainPanel.add(splitPane, BorderLayout.CENTER);

        getContentPane().add(mainPanel);

        // Start with entry point
//        graph.expandFunction(this.codeStructure.getFunctions().get(this.codeStructure.getEntryPoint()), this);

        pack();
    }

    private Orientation getCurrentOrientation() {
        Orientation currentOrientation;
        try {
            currentOrientation = Orientation.valueOf(ui.getPrefs().getCodeStructureGraphOrientation());
        } catch (Exception e) {
            currentOrientation = Orientation.HORIZONTAL;
        }
        return currentOrientation;
    }

    /** for debugging only */
    private void createFakeStructure() {
        codeStructure = new CodeStructure(0);
        Function sourceFunction = new Function(0, "main", "comment");
        codeStructure.getFunctions().put(0, sourceFunction);
        for (int i = 1; i <= 10; i++) {
            int address = i * 10;
            Function function = new Function(address, "Function" + i, "");
            codeStructure.getFunctions().put(address, function);
            sourceFunction.getCalls().add(new Jump(0, address, false));
            for (int j = 1; i <= 10; i++) {
                int address2 = i * 10 + j;
                Function function2 = new Function(address2, "SubFunction" + j, "");
                codeStructure.getFunctions().put(address2, function2);
                function.getCalls().add(new Jump(address, address2, false));
            }
        }
    }

    public Component getGraphPane() {
        parent = graph.getDefaultParent();

        // Prevent manual cell resizing
        graph.setCellsResizable(false);
        // Prevent manual cell moving
        graph.setCellsMovable(false);

        graph.setMinimumGraphSize(new mxRectangle(0, 0, FRAME_WIDTH/2, FRAME_HEIGHT));

        graphComponent = new CodeStructureMxGraphComponent(graph, this);
        // Prevent edge drawing from UI
        graphComponent.setConnectable(false);
        graphComponent.setAutoScroll(true);
        graphComponent.setDragEnabled(false);
        return graphComponent;
    }


    private Component getListingPane() {
        return new JScrollPane(listingArea, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    }


    public void addCall(Function sourceFunction, Jump call, Object targetCell) {
        graph.insertEdge(parent, null, "", cellObjects.get(sourceFunction.getAddress()), targetCell);
        renderedCalls.add(call);
    }


    public Object addFunction(Function function) {
        // Function cells are created white and remain so until they are expanded
        Object vertex = graph.insertVertex(parent, "" + function.getAddress(), function, 0, 0, FUNCTION_CELL_WIDTH, FUNCTION_CELL_HEIGHT, "defaultVertex;" + mxConstants.STYLE_FILLCOLOR + "=#FFFFFF");
        cellObjects.put(function.getAddress(), vertex);
        return vertex;
    }

    public Object addFakeFunction(int address) {
        // Fake functions are targets that haven't been disassembled as code
        Object vertex;
        Object value;
        if (address == 0) {
            value = "??";
            vertex = graph.insertVertex(parent, new Object().toString(), value, 0, 0, FAKE_FUNCTION_CELL_WIDTH, FAKE_FUNCTION_CELL_HEIGHT, "defaultVertex;" + mxConstants.STYLE_FILLCOLOR + "=#FF7700");
        }
        else {
            value = address;
            vertex = graph.insertVertex(parent, "" + address, value, 0, 0, FAKE_FUNCTION_CELL_WIDTH, FAKE_FUNCTION_CELL_HEIGHT, "defaultVertex;" + mxConstants.STYLE_FILLCOLOR + "=#FF0000");
        }
        cellObjects.put(value, vertex);
        return vertex;
    }


    public void writeFunction(Function function) throws IOException {
        listingArea.clear();
        Writer writer = listingArea.getWriter();
        List<CodeSegment> segments = function.getCodeSegments();
        if (segments.size() == 0) {
            writer.write("; function at address 0x" + Format.asHex(function.getAddress(), 8) + " was not disassembled (not in CODE range)");
        }
        else {
            for (int i = 0; i < segments.size(); i++) {
                CodeSegment codeSegment = segments.get(i);
                if (segments.size() > 1) {
                    writer.write("; Segment #" + i + "\n");
                }
                for (int address = codeSegment.getStart(); address <= codeSegment.getEnd(); address = codeStructure.getInstructions().higherKey(address)) {
                    DisassembledInstruction instruction = codeStructure.getInstructions().get(address);
                    try {
                        codeStructure.writeInstruction(writer, address, instruction, 0);
                    } catch (IOException e) {
                        writer.write("# ERROR decoding instruction at address 0x" + Format.asHex(address, 8) + " : " + e.getMessage());
                    }
                }
                writer.write("\n");
            }
        }
    }

    public void writeText(String text) throws IOException {
        listingArea.clear();
        Writer writer = listingArea.getWriter();
        writer.write(text);
    }



    private void saveSvg(File file) throws IOException {
        try {
            // Save as SVG
            mxSvgCanvas canvas = (mxSvgCanvas) mxCellRenderer.drawCells(graph, null, 1, null,
                    new mxCellRenderer.CanvasFactory() {
                        public mxICanvas createCanvas(int width, int height) {
                            mxSvgCanvas canvas = new mxSvgCanvas(mxUtils.createSvgDocument(width, height));
                            canvas.setEmbedded(true);
                            return canvas;
                        }
                    });
            mxUtils.writeFile(mxUtils.getXml(canvas.getDocument()), file.getAbsolutePath());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void savePng(File file) throws IOException {
        // Creates the image for the PNG file
        BufferedImage image = mxCellRenderer.createBufferedImage(graph, null, 1, Color.WHITE, true, null, graphComponent.getCanvas());

        // Creates the URL-encoded XML data
        mxPngEncodeParam param = mxPngEncodeParam.getDefaultEncodeParam(image);
        param.setCompressedText(new String[] { });

        // Saves as a PNG file
        FileOutputStream outputStream = new FileOutputStream(file);
        try
        {
            mxPngImageEncoder encoder = new mxPngImageEncoder(outputStream, param);

            if (image != null)
            {
                encoder.encode(image);
            }
            else
            {
                JOptionPane.showMessageDialog(CodeStructureFrame.this, "Error rendering image", "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
        finally
        {
            outputStream.close();
        }
    }

    public void makeExpandedStyle(Function function) {
        mxCell cell = getCellById("" + function.getAddress());
        graph.setCellStyles(mxConstants.STYLE_FILLCOLOR, function.getColor(), new Object[]{cell});
    }

    private mxCell getCellById(String id) {
        for (Object c : graph.getChildCells(graph.getDefaultParent(), true, true)) {
            mxCell cell = (mxCell) c; 
            if (id.equals(cell.getId())) {
                return cell;
            }
        }
        return null;
    }
}
