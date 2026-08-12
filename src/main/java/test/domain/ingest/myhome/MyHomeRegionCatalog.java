package test.domain.ingest.myhome;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class MyHomeRegionCatalog {

    private static final String RESOURCE = "/myhome-region-codes.csv";
    private static final String HEADER = "brtcCode,signguCode,brtcName,signguName";
    private static final int REGION_COUNT = 256;

    private final List<MyHomeRegion> regions;

    public MyHomeRegionCatalog() {
        this.regions = load();
    }

    public List<MyHomeRegion> all() {
        return regions;
    }

    private List<MyHomeRegion> load() {
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing MyHome region resource: " + RESOURCE);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                if (!HEADER.equals(reader.readLine())) {
                    throw new IllegalStateException("Invalid MyHome region resource header");
                }
                List<MyHomeRegion> loaded = new ArrayList<>();
                Set<String> fullCodes = new HashSet<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] columns = line.split(",", -1);
                    if (columns.length != 4) {
                        throw new IllegalStateException("Invalid MyHome region resource row: " + line);
                    }
                    MyHomeRegion region = new MyHomeRegion(columns[0], columns[1], columns[2], columns[3]);
                    if (!fullCodes.add(region.fullCode())) {
                        throw new IllegalStateException("Duplicate MyHome region code: " + region.fullCode());
                    }
                    loaded.add(region);
                }
                if (loaded.size() != REGION_COUNT) {
                    throw new IllegalStateException("Expected " + REGION_COUNT + " MyHome regions but found " + loaded.size());
                }
                return List.copyOf(loaded);
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid MyHome region resource", e);
        }
    }
}
