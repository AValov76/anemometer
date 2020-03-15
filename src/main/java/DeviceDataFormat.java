public class DeviceDataFormat {
    private String varName;
    private double value;
    private String unit;

    private DeviceDataFormat() {
    }

    ;

    DeviceDataFormat(String varName, String unit, double value) {
        this.varName = varName;
        this.unit = unit;
        this.value = value;
    }

    @Override
    public String toString() {
        String s ="| "+ varName + " " + value + "(" + unit.toString() + ") |";
        return s;
    }
}
