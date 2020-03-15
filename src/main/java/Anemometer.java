/*интерфейс описывает что должен уметь делать любой анемометр -
уметь отдать значения своих параметров, в формате DataFormat
*/
public interface Anemometer {
    int requestPeriod = 1000;// период выборки в миллисекундах
    DeviceDataFormat[] anemometerData();

    void start();

}
