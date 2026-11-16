using System.Text;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

static class Uuids
{
    public static readonly Guid FEA6 = Guid.Parse("0000fea6-0000-1000-8000-00805f9b34fb");
    public static readonly Guid GP0001 = Guid.Parse("b5f90001-aa8d-11e3-9046-0002a5d5c51b");
    public static readonly Guid GP0090 = Guid.Parse("b5f90090-aa8d-11e3-9046-0002a5d5c51b");

    public static Guid GP(int suffix) => Guid.Parse($"b5f9{suffix:x4}-aa8d-11e3-9046-0002a5d5c51b");
}

static class Program
{
    private static readonly List<GattServiceProvider> Providers = new();

    static async Task<int> Main()
    {
        Console.OutputEncoding = Encoding.UTF8;
        Console.WriteLine("GoPro Wake BLE Probe");
        Console.WriteLine("====================");

        var adapter = await BluetoothAdapter.GetDefaultAsync();
        if (adapter is null)
        {
            Console.WriteLine("ERROR: No Bluetooth adapter found.");
            return 2;
        }

        Console.WriteLine($"Peripheral role supported: {adapter.IsPeripheralRoleSupported}");
        if (!adapter.IsPeripheralRoleSupported)
        {
            Console.WriteLine("ERROR: This Bluetooth adapter/driver cannot act as a BLE peripheral.");
            return 3;
        }

        try
        {
            await CreateControlService();
            await CreateWifiService();
            await CreateManagementService();

            foreach (var provider in Providers)
            {
                provider.AdvertisementStatusChanged += (_, e) =>
                    Console.WriteLine($"Advertising status: {e.Status} / {e.Error}");
                provider.StartAdvertising(new GattServiceProviderAdvertisingParameters
                {
                    IsDiscoverable = true,
                    IsConnectable = true,
                });
            }

            Console.WriteLine();
            Console.WriteLine("Advertising GoPro-like GATT services.");
            Console.WriteLine("Primary discovery UUID: FEA6");
            Console.WriteLine("Open Wake on the phone and search for GoPro.");
            Console.WriteLine("Press ENTER to stop.");
            Console.ReadLine();

            foreach (var provider in Providers)
                provider.StopAdvertising();

            return 0;
        }
        catch (Exception ex)
        {
            Console.WriteLine();
            Console.WriteLine("ERROR:");
            Console.WriteLine(ex);
            return 10;
        }
    }

    private static async Task<GattServiceProvider> CreateService(Guid serviceUuid)
    {
        var result = await GattServiceProvider.CreateAsync(serviceUuid);
        if (result.Error != BluetoothError.Success || result.ServiceProvider is null)
            throw new InvalidOperationException($"CreateAsync({serviceUuid}) failed: {result.Error}");

        Providers.Add(result.ServiceProvider);
        Console.WriteLine($"Created service {serviceUuid}");
        return result.ServiceProvider;
    }

    private static async Task CreateControlService()
    {
        var p = await CreateService(Uuids.FEA6);
        await AddWrite(p, Uuids.GP(0x0072), "Command");
        await AddNotify(p, Uuids.GP(0x0073), "Command Response");
        await AddWrite(p, Uuids.GP(0x0074), "Settings");
        await AddNotify(p, Uuids.GP(0x0075), "Settings Response");
        await AddWrite(p, Uuids.GP(0x0076), "Query");
        await AddNotify(p, Uuids.GP(0x0077), "Query Response");
    }

    private static async Task CreateWifiService()
    {
        var p = await CreateService(Uuids.GP0001);
        await AddRead(p, Uuids.GP(0x0002), "GoPro_Wake_Probe", "WiFi SSID");
        await AddRead(p, Uuids.GP(0x0003), "wakeprobe123", "WiFi Password");
        await AddWrite(p, Uuids.GP(0x0004), "WiFi AP Power");
        await AddReadNotify(p, Uuids.GP(0x0005), new byte[] { 0x00 }, "WiFi AP State");
    }

    private static async Task CreateManagementService()
    {
        var p = await CreateService(Uuids.GP0090);
        await AddWrite(p, Uuids.GP(0x0091), "Network Management Command");
        await AddNotify(p, Uuids.GP(0x0092), "Network Management Response");
    }

    private static async Task AddWrite(GattServiceProvider provider, Guid uuid, string label)
    {
        var result = await provider.Service.CreateCharacteristicAsync(
            uuid,
            new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Write |
                                           GattCharacteristicProperties.WriteWithoutResponse,
                WriteProtectionLevel = GattProtectionLevel.Plain,
                UserDescription = label,
            });

        if (result.Error != BluetoothError.Success || result.Characteristic is null)
            throw new InvalidOperationException($"Create write characteristic {label} failed: {result.Error}");

        result.Characteristic.WriteRequested += async (_, args) =>
        {
            var deferral = args.GetDeferral();
            try
            {
                var request = await args.GetRequestAsync();
                if (request is null) return;

                var bytes = new byte[request.Value.Length];
                using var reader = DataReader.FromBuffer(request.Value);
                reader.ReadBytes(bytes);
                Console.WriteLine($"RX {label}: {BitConverter.ToString(bytes).Replace("-", " ")}");
                request.Respond();
            }
            finally
            {
                deferral.Complete();
            }
        };

        Console.WriteLine($"  + {label} {uuid}");
    }

    private static async Task AddNotify(GattServiceProvider provider, Guid uuid, string label)
    {
        var result = await provider.Service.CreateCharacteristicAsync(
            uuid,
            new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Notify,
                ReadProtectionLevel = GattProtectionLevel.Plain,
                UserDescription = label,
            });

        if (result.Error != BluetoothError.Success || result.Characteristic is null)
            throw new InvalidOperationException($"Create notify characteristic {label} failed: {result.Error}");

        result.Characteristic.SubscribedClientsChanged += (_, __) =>
            Console.WriteLine($"Subscribed {label}: {result.Characteristic.SubscribedClients.Count}");

        Console.WriteLine($"  + {label} {uuid}");
    }

    private static async Task AddRead(GattServiceProvider provider, Guid uuid, string value, string label)
    {
        var bytes = Encoding.UTF8.GetBytes(value);
        await AddReadNotify(provider, uuid, bytes, label, false);
    }

    private static async Task AddReadNotify(
        GattServiceProvider provider,
        Guid uuid,
        byte[] value,
        string label,
        bool notify = true)
    {
        using var writer = new DataWriter();
        writer.WriteBytes(value);
        var props = GattCharacteristicProperties.Read;
        if (notify) props |= GattCharacteristicProperties.Notify;

        var result = await provider.Service.CreateCharacteristicAsync(
            uuid,
            new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = props,
                ReadProtectionLevel = GattProtectionLevel.Plain,
                StaticValue = writer.DetachBuffer(),
                UserDescription = label,
            });

        if (result.Error != BluetoothError.Success || result.Characteristic is null)
            throw new InvalidOperationException($"Create read characteristic {label} failed: {result.Error}");

        Console.WriteLine($"  + {label} {uuid}");
    }
}
