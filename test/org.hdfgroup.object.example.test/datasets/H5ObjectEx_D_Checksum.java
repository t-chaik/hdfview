/************************************************************
  This example shows how to read and write data to a dataset
  using the Fletcher32 checksum filter.  The program first
  checks if the Fletcher32 filter is available, then if it
  is it writes integers to a dataset using Fletcher32, then
  closes the file.  Next, it reopens the file, reads back
  the data, checks if the filter detected an error and
  outputs the type of filter and the maximum value in the
  dataset to the screen.
 ************************************************************/
package datasets;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import hdf.hdf5lib.H5;
import hdf.hdf5lib.HDF5Constants;
import hdf.object.Dataset;
import hdf.object.Datatype;
import hdf.object.FileFormat;
import hdf.object.Group;
import hdf.object.h5.H5Datatype;
import hdf.object.h5.H5File;
import hdf.object.h5.H5ScalarDS;

public class H5ObjectEx_D_Checksum {
    private static String FILENAME = "H5ObjectEx_D_Checksum.h5";
    private static String DATASETNAME = "DS1";
    private static final int DIM_X = 32;
    private static final int DIM_Y = 64;
    private static final int CHUNK_X = 4;
    private static final int CHUNK_Y = 8;
    private static final int RANK = 2;
    private static final int NDIMS = 2;
    private static final int DATATYPE_SIZE = 4;

    // Values for the status of space allocation
    enum H5Z_filter {
        H5Z_FILTER_ERROR(-1), H5Z_FILTER_NONE(0), H5Z_FILTER_DEFLATE(1), H5Z_FILTER_SHUFFLE(
                2), H5Z_FILTER_FLETCHER32(3), H5Z_FILTER_SZIP(4), H5Z_FILTER_NBIT(5), H5Z_FILTER_SCALEOFFSET(
                        6), H5Z_FILTER_RESERVED(256), H5Z_FILTER_MAX(65535);
        private static final Map<Integer, H5Z_filter> lookup = new HashMap<Integer, H5Z_filter>();

        static {
            for (H5Z_filter s : EnumSet.allOf(H5Z_filter.class))
                lookup.put(s.getCode(), s);
        }

        private int code;

        H5Z_filter(int layout_type) {
            this.code = layout_type;
        }

        public int getCode() {
            return this.code;
        }

        public static H5Z_filter get(int code) {
            return lookup.get(code);
        }
    }

    private static boolean checkFletcher32Filter() {
        try {
            int available = H5.H5Zfilter_avail(H5Z_filter.H5Z_FILTER_FLETCHER32.getCode());
            if (available == 0) {
                System.out.println("N-Bit filter not available.");
                return false;
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        try {
            int filter_info = H5.H5Zget_filter_info(HDF5Constants.H5Z_FILTER_FLETCHER32);
            if (((filter_info & HDF5Constants.H5Z_FILTER_CONFIG_ENCODE_ENABLED) == 0)
                    || ((filter_info & HDF5Constants.H5Z_FILTER_CONFIG_DECODE_ENABLED) == 0)) {
                System.out
                .println("N-Bit filter not available for encoding and decoding.");
                return false;
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }

    private static void writeChecksum() {
        H5File file = null;
        Dataset dset = null;
        long file_id = -1;
        long filespace_id = -1;
        long dataset_id = -1;
        long dcpl_id = -1;
        long type_id = -1;
        long[] dims = { DIM_X, DIM_Y };
        long[] chunk_dims = { CHUNK_X, CHUNK_Y };
        int[] dset_data = new int[DIM_X*DIM_Y];
        H5Datatype typeInt = null;

        // Initialize data.
        for (int indx = 0; indx < DIM_X; indx++)
            for (int jndx = 0; jndx < DIM_Y; jndx++)
                dset_data[indx*DIM_Y+jndx] = indx * jndx - jndx;

        // Create a new file using default properties.
        try {
            file = new H5File(FILENAME, FileFormat.CREATE);
            file_id = file.open();
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        // Create datatype.
        try {
            typeInt = new H5Datatype(Datatype.CLASS_INTEGER, DATATYPE_SIZE, Datatype.ORDER_LE, Datatype.NATIVE);
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        // Create dataspace. Setting maximum size to NULL sets the maximum
        // size to be the current size.
        try {
            filespace_id = H5.H5Screate_simple(RANK, dims, null);
            type_id = typeInt.createNative();
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        // Create the dataset creation property list, add the N-Bit filter.
        try {
            dcpl_id = H5.H5Pcreate(HDF5Constants.H5P_DATASET_CREATE);
            if (dcpl_id >= 0) {
                H5.H5Pset_fletcher32(dcpl_id);
                // Set the chunk size.
                H5.H5Pset_chunk(dcpl_id, NDIMS, chunk_dims);
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        // Create the dataset.
        try {
            if ((file_id >= 0) && (filespace_id >= 0) && (type_id >= 0) && (dcpl_id >= 0)) {
                dataset_id = H5.H5Dcreate(file_id, DATASETNAME, type_id, filespace_id,
                        HDF5Constants.H5P_DEFAULT, dcpl_id, HDF5Constants.H5P_DEFAULT);
                dset = new H5ScalarDS(file, DATASETNAME, "/");
                Group pgroup = (Group) file.get("/");
                pgroup.addToMemberList(dset);
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        // Write the data to the dataset.
        try {
            if (dset != null)
                dset.write(dset_data);
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        // End access to the dataset and release resources used by it.
        try {
            if (dcpl_id >= 0)
                H5.H5Pclose(dcpl_id);
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        try {
            if (type_id >= 0)
                H5.H5Tclose(type_id);
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        try {
            if (dataset_id >= 0)
                dset.close(dataset_id);
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        try {
            if (filespace_id >= 0)
                H5.H5Sclose(filespace_id);
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        // Close the file.
        try {
            file.close();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void readChecksum() {
        H5File file = null;
        Dataset dset = null;
        long file_id = -1;
        long dataset_id = -1;
        long dcpl_id = -1;
        int[] dset_data = new int[DIM_X*DIM_Y];

        // Open an existing file.
        try {
            file = new H5File(FILENAME, FileFormat.READ);
            file_id = file.open();
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        // Open an existing dataset.
        try {
            dset = (Dataset) file.get(DATASETNAME);
            dataset_id = dset.open();
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        // Retrieve the dataset creation property list.
        try {
            if (dataset_id >= 0)
                dcpl_id = H5.H5Dget_create_plist(dataset_id);
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        // Retrieve and print the filter type. Here we only retrieve the
        // first filter because we know that we only added one filter.
        try {
            if (dcpl_id >= 0) {
                // Java lib requires a valid filter_name object and cd_values
                int[] flags = { 0 };
                long[] cd_nelmts = { 1 };
                int[] cd_values = { 0 };
                String[] filter_name = { "" };
                int[] filter_config = { 0 };
                int filter_type = -1;
                filter_type = H5.H5Pget_filter(dcpl_id, 0, flags, cd_nelmts, cd_values,
                        120, filter_name, filter_config);
                System.out.print("Filter type is: ");
                switch (H5Z_filter.get(filter_type)) {
                    case H5Z_FILTER_DEFLATE:
                        System.out.println("H5Z_FILTER_DEFLATE");
                        break;
                    case H5Z_FILTER_SHUFFLE:
                        System.out.println("H5Z_FILTER_SHUFFLE");
                        break;
                    case H5Z_FILTER_FLETCHER32:
                        System.out.println("H5Z_FILTER_FLETCHER32");
                        break;
                    case H5Z_FILTER_SZIP:
                        System.out.println("H5Z_FILTER_SZIP");
                        break;
                    default:
                        System.out.println("H5Z_FILTER_ERROR");
                }
                System.out.println();
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        try {
            if (dcpl_id >= 0)
                H5.H5Pclose(dcpl_id);
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        // Read the data using the default properties.
        try {
            dset.init();
            dset_data = (int[]) dset.getData();
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        // Find the maximum value in the dataset, to verify that it was read
        // correctly.
        int max = dset_data[0];
        for (int indx = 0; indx < DIM_X; indx++) {
            for (int jndx = 0; jndx < DIM_Y; jndx++)
                if (max < dset_data[indx*DIM_Y+jndx])
                    max = dset_data[indx*DIM_Y+jndx];
        }
        // Print the maximum value.
        System.out.println("Maximum value in " + DATASETNAME + " is: " + max);

        try {
            if (dataset_id >= 0)
                dset.close(dataset_id);
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        // Close the file.
        try {
            file.close();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        // Check if the Fletcher32 filter is available and can be used for
        // both encoding and decoding. Normally we do not perform error
        // checking in these examples for the sake of clarity, but in this
        // case we will make an exception because this filter is an
        // optional part of the hdf5 library.
        // size to be the current size.
        if (H5ObjectEx_D_Checksum.checkFletcher32Filter()) {
            H5ObjectEx_D_Checksum.writeChecksum();
            H5ObjectEx_D_Checksum.readChecksum();
        }
    }

}
