import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortTimeoutException;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public class AnemometerRS485 implements Anemometer, Runnable {
    static boolean testmode = true;
    private static byte[] requestBytes = {0x02, 0x03, 0x00, 0x2A, 0x00, 0x02};

    //настройки порта
    private volatile int baudRate = 9600;
    private volatile int dataBits = 8;
    private volatile int stopBits = 1;
    private volatile int parity = 0;
    private volatile int eventFlags = 0;
    private volatile int timeoutMode = 0;
    private volatile int readTimeout = 0;
    private volatile int writeTimeout = 0;
    private volatile int flowControl = 0;
    private SerialPort comPort;
    //данные с анемометра
    private volatile double windSpeed = 0;
    private volatile double windDirection = 0;
    private volatile int reqestCount = 0; //счетчик запросов
    private volatile int errorCount = 0;
    private volatile boolean readError = false; // ошибка контрольной суммы ответа
    private volatile boolean timeOutReadError = false; //ошибка таймаута ответа
    private volatile double errorRate = 0;

    AnemometerRS485() {
        testTask();
        testPrint("Подключение к порту анемометра");
        comPort = SerialPort.getCommPorts()[1];
    }

    AnemometerRS485(String port, int baudRate, int dataBits, int stopBits, int parity) {
        this.baudRate = baudRate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;
    }

    static void testPrint(Object o) {
        if (testmode) System.out.println(o);
    }

    public static void main(String[] args) {
        testTask();
        testPrint("Подключение к порту анемометра");
        Anemometer anemometer = new AnemometerRS485();
    }

    static void testTask() {
        if (!testmode) return;
        System.out.print("Проверка блока CRC:");
        String[] strings = {
                Integer.toHexString(RS485CRC16.calculateCRC(requestBytes, 0, requestBytes.length)[0]),
                Integer.toHexString(RS485CRC16.calculateCRC(requestBytes, 0, requestBytes.length)[1])
        };
        testPrint(Arrays.toString(strings));
        testPrint("Список доступных портов:");
        String s = new String();
        s = "[";
        SerialPort[] comPorts = SerialPort.getCommPorts();
        for (SerialPort comPort : comPorts
        ) {
            s = s + comPort.toString() + ",";
        }
        s = s.substring(0, s.length() - 1) + "]";
        System.out.println(s);
    }

    synchronized public DeviceDataFormat[] anemometerData() {

        if (!readError) {
            DeviceDataFormat[] ad = {
                    new DeviceDataFormat("Скорость ветра", "м/c", windSpeed),
                    new DeviceDataFormat("Направление ветра", "градусов", windDirection),
                    new DeviceDataFormat("Качество линни связи", "ошибок/сек", errorRate)
            };
            return ad;
        } else if (!timeOutReadError) {
            DeviceDataFormat[] ad = {new DeviceDataFormat("CRCERROR", "%", errorRate)};
            return ad;
        } else {
            DeviceDataFormat[] ad = {new DeviceDataFormat("TIMEOUTERROR", "%", errorRate)};
            return ad;
        }

    }

    public void start() {
        //
        Thread anemometerDataRead = new Thread(this);
        anemometerDataRead.start();
    }

    // периодически обновляет данные в регистрах анемометра
    public void run() {
        testPrint("Поток чтения данных с анемометра заработал.");

        while (true) {
            // спим requestPeriod миллисекунд
            try {
                Thread.sleep(requestPeriod);
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Поток чтения данных с анемометра прерван.");
                return;
            }
            // наполняем регистры новой инфой
            try {
                //testPrint("Запрос данных с анемометра");
                readData();
                readError = false;
                timeOutReadError = false;
            } catch (CRCERROR e) {
                //testPrint("Ошибка контрольной суммы ответа анемометра");
                reqestCount++;
                errorCount++;
                readError = true;
                timeOutReadError = false;
                setErrorRate();
            } catch (SerialPortTimeoutException e) {
                reqestCount++;
                errorCount++;
                readError = true;
                timeOutReadError = true;
                setErrorRate();

            }

        }
    }

    synchronized private void readData() throws CRCERROR, SerialPortTimeoutException {

        // https://github.com/Fazecast/jSerialComm/wiki/Java-InputStream-and-OutputStream-Interfacing-Usage-Example

        //послать запрос в порт
        try {
            comPort.openPort();
            comPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 100);
            InputStream in = comPort.getInputStream();
            OutputStream out = comPort.getOutputStream();
            byte[] outBytes = bytesWithCRC(requestBytes);
            int ints[] = bytetoInt(outBytes);
            //testPrint("Запрос:" + Arrays.toString(ints));
            out.write(outBytes, 0, outBytes.length);
            //testPrint("Передано. Принимаем ответ:")

            //получаем ответ
            int[] in_stream = new int[9];
            for (int i = 0; i < 9; i++)
                in_stream[i] = in.read();
            //обработка ответа
            //testPrint("Ответ:" + Arrays.toString(in_stream));
            if (!checkCRC(in_stream)) throw new CRCERROR();
            //testPrint("Ответ:" + Arrays.toString(in_stream));
            calcWindSpeed(in_stream);
            //testPrint("Закрываем порт");
            out.close();
            in.close();
            comPort.closePort();

        } catch (CRCERROR e) { //ошибка контрольной суммы ответа
            throw e;
        } catch (SerialPortTimeoutException e) { // ошибка таймаута чтения с анемометра
            throw e;
        } catch (Exception e) {
            e.printStackTrace(); // любая другая ошибка
        }
    }

    // расчет процента ошибок
    synchronized private void setErrorRate() {
        double time = (double) reqestCount * Anemometer.requestPeriod;
        if (time != 0)
            errorRate = ((double) errorCount / time) * 1000 * 100;
        testPrint(errorRate);
    }

    // подготовка пакета к отправке - добавление
    byte[] bytesWithCRC(byte[] bytesWithoutCRC) {
        //создание нового массива на 2 байта больше
        byte[] bytesWithCRC = Arrays.copyOf(bytesWithoutCRC, bytesWithoutCRC.length + 2);
        //расчет контрольной суммы
        int[] crc = RS485CRC16.calculateCRC(bytesWithoutCRC, 0, bytesWithoutCRC.length);
        //вставка контрольной суммы в пакет
        bytesWithCRC[bytesWithCRC.length - 2] = (byte) (crc[0]);
        bytesWithCRC[bytesWithCRC.length - 1] = (byte) (crc[1]);
        return bytesWithCRC;
    }

    // в яве byte строго -127...127, поэтому приходится заморачиваться...
    int[] bytetoInt(byte[] b) {
        int[] ints = new int[b.length];
        for (int i = 0; i < b.length; i++) {

            if (b[i] >= 0) ints[i] = b[i];
            else ints[i] = (b[i] & 0xff);
        }
        return ints;
    }

    // обратная процедура - из массива int в массив байт
    byte[] inttoByte(int[] ints) {
        byte[] b = new byte[ints.length];
        for (int i = 0; i < ints.length; i++) b[i] = (byte) ints[i];
        return b;
    }

    //обработать ответ
    synchronized void calcWindSpeed(int[] in_stream) {
        windDirection = (double) (in_stream[6] + in_stream[5] * 256) / 10;
        windSpeed = (double) (in_stream[4] + in_stream[3] * 256) / 10;
    }

    ;

    //проверка CRC
    synchronized boolean checkCRC(int[] in_stream) {
        byte[] check = inttoByte(Arrays.copyOf(in_stream, in_stream.length - 2));
        int[] crc = RS485CRC16.calculateCRC(check, 0, check.length);

        // надо проверить этот блок на корректную обработку ошибок
        if (
                (in_stream[in_stream.length - 2] == crc[0]) &
                        (in_stream[in_stream.length - 1] == crc[1]))
            return true;

        else return false;
    }

}

class CRCERROR extends Exception {
}
