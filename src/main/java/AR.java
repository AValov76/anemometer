public class AR {
    public static void main(String[] args) {
        Anemometer a = new AnemometerRS485();
        // запуск потока на чтение данных с анемометра
        a.start();
        while (true) {

            DeviceDataFormat[] data = a.anemometerData();
            System.out.print("|");
            for (DeviceDataFormat d : data
            ) {
                System.out.print(d);
            }
            System.out.println("|");
            try {
                Thread.sleep(1000);
            } catch (Exception e) {
            }
        }
    }
}
