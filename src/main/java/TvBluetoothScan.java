
import org.simplejavable.Adapter;
import org.simplejavable.Peripheral;

import java.util.List;

public class TvBluetoothScan {

    private static final String TV_NAME = "AQUOS-TVE21C";

    public static void main(String[] args) throws Exception {
        List<Adapter> adapters = Adapter.getAdapters();

        if (adapters.isEmpty()) {
            System.err.println("No Bluetooth adapter found.");
            return;
        }

        Adapter adapter = adapters.get(0);

        System.out.println("Scanning for " + TV_NAME + "...");
        adapter.scanFor(10000);


        boolean found = false;

        for (Peripheral device : adapter.scanGetResults()) {
            String name = device.getIdentifier();
            System.out.println(
                    "Device: " + (name == null ? "(unnamed)" : name));
            System.out.println("  Address: " + device.getAddress());
            System.out.println("  Connectable: " + device.isConnectable());

            if (name != null
                    && name.toLowerCase(java.util.Locale.ROOT)
                    .contains("aquos")) {
             //   aquosFound = true;
                System.out.println("  *** AQUOS MATCH ***");
            }

            if (!TV_NAME.equals(name)) {
                continue;
            }

            found = true;

            System.out.println("\n================================");
            System.out.println("Found Sharp TV: " + name);
            System.out.println("Address: " + device.getAddress());
            System.out.println("Connectable: " + device.isConnectable());

            if (!device.isConnectable()) {
                System.out.println(
                        "TV is not currently connectable over BLE.");
                continue;
            }

            try {
                device.connect();

                System.out.println(
                        "Connected: " + device.isConnected());

                var services = device.services();

                if (services == null || services.isEmpty()) {
                    System.out.println("No GATT services found.");
                    continue;
                }

                for (var service : services) {
                    System.out.println(
                            "\nService UUID: " + service.uuid());

                    var characteristics = service.characteristics();

                    if (characteristics == null
                            || characteristics.isEmpty()) {
                        System.out.println("  No characteristics.");
                        continue;
                    }

                    for (var characteristic : characteristics) {
                        System.out.println(
                                "\n  Characteristic UUID: "
                                        + characteristic.uuid());

                        System.out.println(
                                "    Read: "
                                        + characteristic.canRead());
                        System.out.println(
                                "    Notify: "
                                        + characteristic.canNotify());
                        System.out.println(
                                "    Indicate: "
                                        + characteristic.canIndicate());
                        System.out.println(
                                "    Write request: "
                                        + characteristic.canWriteRequest());
                        System.out.println(
                                "    Write command: "
                                        + characteristic.canWriteCommand());

                        System.out.println("    Available API methods:");

                        for (var method :
                                characteristic.getClass().getMethods()) {
                            String methodName =
                                    method.getName().toLowerCase(
                                            java.util.Locale.ROOT);

                            if (methodName.contains("read")
                                    || methodName.contains("value")
                                    || methodName.contains("notify")
                                    || methodName.contains("indicat")
                                    || methodName.contains("write")) {
                                System.out.println("      " + method);
                            }
                        }
                    }
                }

            } catch (Exception e) {
                System.err.println(
                        "Inspection failed: " + e.getMessage());
                e.printStackTrace(System.err);
            } finally {
                try {
                    if (device.isConnected()) {
                        device.disconnect();
                        System.out.println("\nDisconnected.");
                    }
                } catch (Exception e) {
                    System.err.println(
                            "Disconnect failed: " + e.getMessage());
                }
            }

            // Inspect only the first matching advertisement.
            break;
        }

        if (!found) {
            System.out.println(
                    "\n" + TV_NAME + " was not found in this scan.");
            System.out.println(
                    "Keep the TV's Bluetooth pairing screen open and retry.");
        }
    }
}
