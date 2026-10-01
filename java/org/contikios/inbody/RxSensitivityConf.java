package org.contikios.inbody;

import java.awt.BorderLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import org.contikios.cooja.ClassDescription;
import org.contikios.cooja.Cooja;
import org.contikios.cooja.PluginType;
import org.contikios.cooja.Simulation;
import org.contikios.cooja.SupportedArguments;
import org.contikios.cooja.VisPlugin;
import org.contikios.cooja.interfaces.Radio;
import org.contikios.cooja.radiomediums.AbstractRadioMedium;

/**
 * Plugin for configuring per-mote receiver sensitivity in the Fat-IBC radio medium
 */
@ClassDescription("RX Sensitivity")
@PluginType(PluginType.PType.SIM_PLUGIN)
@SupportedArguments(radioMediums = {InBody.class})
public class RxSensitivityConf extends VisPlugin {

    private static final int IDX_MOTE = 0;
    private static final int IDX_SENS = 1;
    private static final String[] COLUMN_NAMES = {"Mote", "RX Sensitivity (dBm)"};

    private final InBody inBody;
    private final AbstractRadioMedium radioMedium;
    private final Simulation sim;

    public RxSensitivityConf(Simulation sim, Cooja gui) {
        super("RX Sensitivity Configurator", gui);
        this.sim = sim;
        this.inBody = (InBody) sim.getRadioMedium();
        this.radioMedium = inBody;

        final var model = new AbstractTableModel() {
            @Override
            public String getColumnName(int column) {
                return column >= 0 && column < COLUMN_NAMES.length ? COLUMN_NAMES[column] : "";
            }

            @Override
            public int getRowCount() {
                return radioMedium.getRegisteredRadios().length;
            }

            @Override
            public int getColumnCount() {
                return COLUMN_NAMES.length;
            }

            @Override
            public Object getValueAt(int row, int column) {
                Radio[] radios = radioMedium.getRegisteredRadios();
                if (row < 0 || row >= radios.length) return "";
                Radio radio = radios[row];
                return column == IDX_MOTE ? radio.getMote() : inBody.getRxSensitivity(radio);
            }

            @Override
            public void setValueAt(Object value, int row, int column) {
                Radio[] radios = radioMedium.getRegisteredRadios();
                if (row < 0 || row >= radios.length || column != IDX_SENS) return;
                if (value instanceof Number num) {
                    inBody.setRxSensitivity(radios[row], num.doubleValue());
                }
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                Radio[] radios = radioMedium.getRegisteredRadios();
                if (row < 0 || row >= radios.length) return false;
                gui.signalMoteHighlight(radios[row].getMote());
                return column == IDX_SENS;
            }

            @Override
            public Class<?> getColumnClass(int column) {
                return column == IDX_SENS ? Double.class : Object.class;
            }
        };

        radioMedium.getRadioMediumTriggers().addTrigger(this, (obs, obj) -> model.fireTableDataChanged());
        sim.getMoteTriggers().addTrigger(this, (o, m) -> model.fireTableDataChanged());

        final var combo = new JComboBox<Number>();
        combo.setEditable(true);

        var table = new JTable(model) {
            @Override
            public TableCellEditor getCellEditor(int row, int column) {
                combo.removeAllItems();
                if (column == IDX_SENS) {
                    for (double d = AbstractRadioMedium.SS_STRONG;
                         d >= AbstractRadioMedium.SS_NOTHING; d -= 1.0) {
                        combo.addItem((int) d);
                    }
                }
                return super.getCellEditor(row, column);
            }
        };
        table.setFillsViewportHeight(true);
        
        table.getColumnModel().getColumn(IDX_SENS)
                .setCellRenderer(new DefaultTableCellRenderer() {
                    @Override
                    public void setValue(Object value) {
                        setText(value instanceof Double d
                                ? String.format("%.1f dBm", d)
                                : String.valueOf(value));
                    }
                });
        table.getColumnModel().getColumn(IDX_SENS)
                .setCellEditor(new DefaultCellEditor(combo));

        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        table.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        add(BorderLayout.CENTER, new JScrollPane(table));
        model.fireTableDataChanged();
        setSize(400, 300);
    }

    @Override
    public void closePlugin() {
        radioMedium.getRadioMediumTriggers().deleteTriggers(this);
        sim.getMoteTriggers().deleteTriggers(this);
    }
}
