/*****************************************************************************
 * Copyright by The HDF Group.                                               *
 * All rights reserved.                                                      *
 *                                                                           *
 * This file is part of the HDF Java Products distribution.                  *
 * The full copyright notice, including terms governing use, modification,   *
 * and redistribution, is contained in the files COPYING and Copyright.html. *
 * COPYING can be found at the root of the source code distribution tree.    *
 * Or, see https://support.hdfgroup.org/products/licenses.html               *
 * If you do not have access to either file, you may request a copy from     *
 * help@hdfgroup.org.                                                        *
 ****************************************************************************/

package hdf.view.TableView;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.StringTokenizer;

import org.eclipse.nebula.widgets.nattable.data.IDataProvider;

import hdf.object.CompoundDataFormat;
import hdf.object.DataFormat;
import hdf.object.Datatype;
import hdf.object.Utils;
import hdf.object.h5.H5ReferenceType;
import hdf.view.Tools;

/**
 * A Factory class to return a concrete class implementing the IDataProvider
 * interface in order to provide data for a NatTable.
 *
 * @author Jordan T. Henderson
 * @version 1.0 2/9/2019
 *
 */
public class DataProviderFactory
{
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DataProviderFactory.class);

    /**
     * To keep things clean from an API perspective, keep a static reference to the last
     * DataFormat that was passed in. This keeps us from needing to pass the DataFormat
     * object as a parameter to every DataProvider class, since it's really only needed
     * during the HDFDataProvider constructor.
     */
    private static DataFormat dataFormatReference = null;

    /**
     * Get the Data Display Provider for the supplied data object
     *
     * @param dataObject
     *        the data object
     * @param dataBuf
     *        the data buffer to use
     * @param dataTransposed
     *        if the data should be transposed
     *
     * @return the provider instance
     *
     * @throws Exception if a failure occurred
     */
    public static HDFDataProvider getDataProvider(final DataFormat dataObject, final Object dataBuf, final boolean dataTransposed) throws Exception {
        if (dataObject == null) {
            log.debug("getDataProvider(DataFormat): data object is null");
            return null;
        }

        dataFormatReference = dataObject;

        HDFDataProvider dataProvider = getDataProvider(dataObject.getDatatype(), dataBuf, dataTransposed);

        return dataProvider;
    }

    private static final HDFDataProvider getDataProvider(final Datatype dtype, final Object dataBuf, final boolean dataTransposed) throws Exception {
        HDFDataProvider dataProvider = null;

        try {
            if (dtype.isCompound())
                dataProvider = new CompoundDataProvider(dtype, dataBuf, dataTransposed);
            else if (dtype.isArray())
                dataProvider = new ArrayDataProvider(dtype, dataBuf, dataTransposed);
            else if (dtype.isVLEN() && !dtype.isVarStr())
                dataProvider = new VlenDataProvider(dtype, dataBuf, dataTransposed);
            else if (dtype.isString() || dtype.isVarStr())
                dataProvider = new StringDataProvider(dtype, dataBuf, dataTransposed);
            else if (dtype.isChar())
                dataProvider = new CharDataProvider(dtype, dataBuf, dataTransposed);
            else if (dtype.isInteger() || dtype.isFloat())
                dataProvider = new NumericalDataProvider(dtype, dataBuf, dataTransposed);
            else if (dtype.isEnum())
                dataProvider = new EnumDataProvider(dtype, dataBuf, dataTransposed);
            else if (dtype.isOpaque() || dtype.isBitField())
                dataProvider = new BitfieldDataProvider(dtype, dataBuf, dataTransposed);
            else if (dtype.isRef())
                dataProvider = new RefDataProvider(dtype, dataBuf, dataTransposed);
        }
        catch (Exception ex) {
            log.debug("getDataProvider(): error occurred in retrieving a DataProvider: ", ex);
            dataProvider = null;
        }

        /*
         * Try to use a default DataProvider.
         */
        if (dataProvider == null) {
            log.debug("getDataProvider(): using a default data provider");

            dataProvider = new HDFDataProvider(dtype, dataBuf, dataTransposed);
        }

        return dataProvider;
    }

    /**
     * The base DataProvider which pulls data from a given Array object using direct
     * indices.
     */
    public static class HDFDataProvider implements IDataProvider
    {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HDFDataProvider.class);

        /**
         * In order to support 3-dimensional datasets, which may need to update the data
         * buffer object after flipping through a 'page', this field is not marked as
         * final. However, it is important that subclasses DO NOT override this field.
         */
        protected Object           dataBuf;

        /** the data value */
        protected Object           theValue;

        /** the data format class */
        protected final Class      originalFormatClass;

        /** if the data value has changed */
        protected boolean          isValueChanged;

        /** the type of the parent*/
        protected final boolean    isContainerType;

        /** the rank */
        protected final int        rank;

        /** if the data is in original order */
        protected final boolean    isNaturalOrder;
        /** if the data is transposed */
        protected final boolean    isDataTransposed;

        /** the column */
        protected long       colCount;
        /** the row */
        protected long       rowCount;

        /**
         * Create the HDF extended Data Display Provider for the supplied data object
         *
         * @param dtype
         *        the datatype object
         * @param dataBuf
         *        the data buffer to use
         * @param dataTransposed
         *        if the data should be transposed
         *
         * @throws Exception if a failure occurred
         */
        HDFDataProvider(final Datatype dtype, final Object dataBuf, final boolean dataTransposed) throws Exception {
            this.dataBuf = dataBuf;

            this.originalFormatClass = dataFormatReference.getOriginalClass();

            char runtimeTypeClass = Utils.getJavaObjectRuntimeClass(dataBuf);
            if (runtimeTypeClass == ' ') {
                log.debug("invalid data value runtime type class: runtimeTypeClass={}", runtimeTypeClass);
                throw new IllegalStateException("Invalid data value runtime type class: " + runtimeTypeClass);
            }

            rank = dataFormatReference.getRank();

            isNaturalOrder = ((rank == 1) || (dataFormatReference.getSelectedIndex()[0] < dataFormatReference.getSelectedIndex()[1]));
            isDataTransposed = dataTransposed;

            if (rank > 1) {
                rowCount = dataFormatReference.getHeight();
                colCount = dataFormatReference.getWidth();
            }
            else {
                rowCount = (int) dataFormatReference.getSelectedDims()[0];
                colCount = 1;
            }
            log.trace("constructor: rowCount={} colCount={}", rowCount, colCount);

            theValue = null;
            isValueChanged = false;

            isContainerType = (this instanceof CompoundDataProvider
                            || this instanceof ArrayDataProvider
                            || this instanceof VlenDataProvider);
        }

        /**
         * A utility method used to translate a set of physical table coordinates to an
         * index into a data buffer.
         *
         * @param rowIndex
         *        the row
         * @param columnIndex
         *        the column
         *
         * @return physical location in 1D notation
         */
        public int physicalLocationToBufIndex(int rowIndex, int columnIndex) {
            long index = rowIndex * colCount + columnIndex;

            if (rank > 1) {
                log.trace("physicalLocationToBufIndex({}, {}): rank > 1; adjusting for multi-dimensional dataset", rowIndex, columnIndex);

                if (isDataTransposed && isNaturalOrder)
                    index = columnIndex * rowCount + rowIndex;
                else if (!isDataTransposed && !isNaturalOrder)
                    // Reshape Data
                    index = rowIndex * colCount + columnIndex;
                else if (isDataTransposed && !isNaturalOrder)
                    // Transpose Data
                    index = columnIndex * rowCount + rowIndex;
                else
                    index = rowIndex * colCount + columnIndex;
            }

            log.trace("physicalLocationToBufIndex({}, {}, {}): finish", rowIndex, columnIndex, index);

            return (int) index;
        }

        @Override
        public Object getDataValue(int columnIndex, int rowIndex) {
            try {
                int bufIndex = physicalLocationToBufIndex(rowIndex, columnIndex);

                theValue = Array.get(dataBuf, bufIndex);
            }
            catch (Exception ex) {
                log.debug("getDataValue({}, {}): failure: ", rowIndex, columnIndex, ex);
                theValue = DataFactoryUtils.errStr;
            }

            log.trace("getDataValue({}, {})=({}): finish", rowIndex, columnIndex, theValue);

            return theValue;
        }

        /**
         * When a CompoundDataProvider wants to pass a List of data down to a nested
         * CompoundDataProvider, or when a top-level container DataProvider (such as an
         * ArrayDataProvider) wants to hand data down to a base CompoundDataProvider, we
         * need to pass down a List of data, plus a field and row index. This method is
         * for facilitating this behavior.
         *
         * In general, all "container" DataProviders that have a "container" base
         * DataProvider should call down into their base DataProvider(s) using this
         * method, in order to ensure that buried CompoundDataProviders get handled
         * correctly. When their base DataProvider is not a "container" type, the method
         * getDataValue(Object, index) should be used instead.
         *
         * For atomic type DataProviders, we treat this method as directly calling into
         * getDataValue(Object, index) using the passed rowIndex. However, this method
         * should, in general, not be called by atomic type DataProviders.
         *
         * @param obj
         *        the data object
         * @param rowIndex
         *        the row
         * @param columnIndex
         *        the column
         *
         * @return value of the data
         */
        public Object getDataValue(Object obj, int columnIndex, int rowIndex) {
            return getDataValue(obj, rowIndex);
        }

        /**
         * When a parent HDFDataProvider (such as an ArrayDataProvider) wants to
         * retrieve a data value by routing the operation through its base
         * HDFDataProvider, the parent HDFDataProvider will generally know the direct
         * index to have the base provider use. This method is to facilitate this kind
         * of behavior.
         *
         * Note that this method takes an Object parameter, which is the object that the
         * method should pull its data from. This is to be able to nicely support nested
         * compound DataProviders.
         *
         * @param obj
         *        the data object
         * @param index
         *        the index into the data array
         *
         * @return the data object
         */
        public Object getDataValue(Object obj, int index) {
            try {
                theValue = Array.get(obj, index);
            }
            catch (Exception ex) {
                log.debug("getDataValue({}): failure: ", index, ex);
                theValue = DataFactoryUtils.errStr;
            }

            log.trace("getDataValue({})=({}): finish", index, theValue);

            return theValue;
        }

        /**
         * update the data value of a compound type.
         *
         * @param columnIndex
         *        the column
         * @param rowIndex
         *        the row
         * @param newValue
         *        the new data value object
         */
        @Override
        public void setDataValue(int columnIndex, int rowIndex, Object newValue) {
            try {
                int bufIndex = physicalLocationToBufIndex(rowIndex, columnIndex);

                updateAtomicValue(dataBuf, newValue, bufIndex);
            }
            catch (Exception ex) {
                log.debug("setDataValue({}, {})=({}): cell value update failure: ", rowIndex, columnIndex, newValue, ex);
            }
            log.trace("setDataValue({}, {})=({}): finish", rowIndex, columnIndex, newValue);

            /*
             * TODO: throwing error dialogs when something fails?
             *
             * Tools.showError(shell, "Select", "Unable to set new value:\n\n " + ex);
             */
        }

        /**
         * When a CompoundDataProvider wants to pass a List of data down to a nested
         * CompoundDataProvider, or when a top-level container DataProvider (such as an
         * ArrayDataProvider) wants to hand data down to a base CompoundDataProvider, we
         * need to pass down a List of data and the new value, plus a field and row
         * index. This method is for facilitating this behavior.
         *
         * In general, all "container" DataProviders that have a "container" base
         * DataProvider should call down into their base DataProvider(s) using this{},
         * method, in order to ensure that buried CompoundDataProviders get handled
         * correctly. When their base DataProvider is not a "container" type, the method
         * setDataValue(index, Object, Object) should be used instead.
         *
         * For atomic type DataProviders, we treat this method as directly calling into
         * setDataValue(index, Object, Object) using the passed rowIndex. However, this
         * method should, in general, not be called by atomic type DataProviders.
         *
         * @param columnIndex
         *        the column
         * @param rowIndex
         *        the row
         * @param bufObject
         *        the data object
         * @param newValue
         *        the new data object
         */
        public void setDataValue(int columnIndex, int rowIndex, Object bufObject, Object newValue) {
            setDataValue(rowIndex, bufObject, newValue);
        }

        /**
         * When a parent HDFDataProvider (such as an ArrayDataProvider) wants to set a
         * data value by routing the operation through its base HDFDataProvider, the
         * parent HDFDataProvider will generally know the direct index to have the base
         * provider use. This method is to facilitate this kind of behavior.
         *
         * Note that this method takes two Object parameters, one which is the object
         * that the method should set its data inside of and one which is the new value
         * to set. This is to be able to nicely support nested compound DataProviders.
         *
         * @param index
         *        the index into the data array
         * @param bufObject
         *        the data object
         * @param newValue
         *        the new data object
         */
        public void setDataValue(int index, Object bufObject, Object newValue) {
            try {
                updateAtomicValue(bufObject, newValue, index);
            }
            catch (Exception ex) {
                log.debug("setDataValue({}, {})=({}): updateAtomicValue failure: ", index, bufObject, newValue, ex);
            }
            log.trace("setDataValue({}, {})=({}): finish", index, bufObject, newValue);
        }

        private void updateAtomicValue(Object bufObject, Object newValue, int bufIndex) {
            if ((newValue == null) || ((newValue = ((String) newValue).trim()) == null)) {
                log.debug("updateAtomicValue(): cell value not updated; new value is null");
                return;
            }

            // No need to update if values are the same
            Object oldVal = this.getDataValue(bufObject, bufIndex);
            if ((oldVal != null) && newValue.equals(oldVal.toString())) {
                log.debug("updateAtomicValue(): cell value not updated; new value same as old value");
                return;
            }

            char runtimeTypeClass = Utils.getJavaObjectRuntimeClass(bufObject);

            log.trace("updateAtomicValue(): runtimeTypeClass={}", runtimeTypeClass);

            switch (runtimeTypeClass) {
                case 'B':
                    byte bvalue = 0;
                    bvalue = Byte.parseByte((String) newValue);
                    Array.setByte(bufObject, bufIndex, bvalue);
                    break;
                case 'S':
                    short svalue = 0;
                    svalue = Short.parseShort((String) newValue);
                    Array.setShort(bufObject, bufIndex, svalue);
                    break;
                case 'I':
                    int ivalue = 0;
                    ivalue = Integer.parseInt((String) newValue);
                    Array.setInt(bufObject, bufIndex, ivalue);
                    break;
                case 'J':
                    long lvalue = 0;
                    String cname = this.originalFormatClass.getName();
                    char dname = cname.charAt(cname.lastIndexOf('[') + 1);
                    if (dname == 'J') {
                        BigInteger big = new BigInteger((String) newValue);
                        lvalue = big.longValue();
                    }
                    else
                        lvalue = Long.parseLong((String) newValue);
                    Array.setLong(bufObject, bufIndex, lvalue);
                    break;
                case 'F':
                    float fvalue = 0;
                    fvalue = Float.parseFloat((String) newValue);
                    Array.setFloat(bufObject, bufIndex, fvalue);
                    break;
                case 'D':
                    double dvalue = 0;
                    dvalue = Double.parseDouble((String) newValue);
                    Array.setDouble(bufObject, bufIndex, dvalue);
                    break;
                default:
                    Array.set(bufObject, bufIndex, newValue);
                    break;
            }

            isValueChanged = true;
        }

        @Override
        public int getColumnCount() {
            return (int) colCount;
        }

        @Override
        public int getRowCount() {
            return (int) rowCount;
        }

        /**
         * set if the data value has changed
         *
         * @param isChanged
         *        if the data value is changed
         */
        public final void setIsValueChanged(boolean isChanged) {
            isValueChanged = isChanged;
        }

        /**
         * @return if the datavalue has chaged
         */
        public final boolean getIsValueChanged() {
            return isValueChanged;
        }

        /**
         * Update the data buffer for this HDFDataProvider. This is necessary for when
         * the data that has been read is invalidated, such as when flipping through
         * 'pages' in a > 2-dimensional dataset.
         *
         * @param newBuf
         *        the new data buffer
         */
        public final void updateDataBuffer(Object newBuf) {
            this.dataBuf = newBuf;

            if (rank > 1) {
                rowCount = dataFormatReference.getHeight();
                colCount = dataFormatReference.getWidth();
            }
            else {
                rowCount = (int) dataFormatReference.getSelectedDims()[0];
                colCount = 1;
            }
            log.trace("updateDataBuffer: rowCount={} colCount={}", rowCount, colCount);
        }
    }

    /*
     * A DataProvider for Compound datatype datasets which is a composite of
     * DataProviders, one for each selected member of the Compound datatype.
     */
    private static class CompoundDataProvider extends HDFDataProvider
    {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompoundDataProvider.class);

        private final HashMap<Integer, Integer> baseProviderIndexMap;
        private final HashMap<Integer, Integer> relCmpdStartIndexMap;

        private final HDFDataProvider[]         baseTypeProviders;

        private final Datatype[]                selectedMemberTypes;

        private final int[]                     selectedMemberOrders;

        private final int                       nSubColumns;
        private final int                       nCols;
        private final int                       nRows;

        CompoundDataProvider(final Datatype dtype, final Object dataBuf, final boolean dataTransposed) throws Exception {
            super(dtype, dataBuf, dataTransposed);

            CompoundDataFormat compoundFormat = (CompoundDataFormat) dataFormatReference;
            selectedMemberTypes = compoundFormat.getSelectedMemberTypes();
            selectedMemberOrders = compoundFormat.getSelectedMemberOrders();

            List<Datatype> localSelectedTypes = DataFactoryUtils.filterNonSelectedMembers(compoundFormat, dtype);

            log.trace("setting up {} base HDFDataProviders", localSelectedTypes.size());

            baseTypeProviders = new HDFDataProvider[localSelectedTypes.size()];
            for (int i = 0; i < baseTypeProviders.length; i++) {
                log.trace("retrieving DataProvider for member {}", i);

                try {
                    baseTypeProviders[i] = getDataProvider(localSelectedTypes.get(i), dataBuf, dataTransposed);
                }
                catch (Exception ex) {
                    log.debug("failed to retrieve DataProvider for member {}: ", i, ex);
                    baseTypeProviders[i] = null;
                }
            }

            /*
             * Build necessary index maps.
             */
            HashMap<Integer, Integer>[] maps = DataFactoryUtils.buildIndexMaps(compoundFormat, localSelectedTypes);
            baseProviderIndexMap = maps[DataFactoryUtils.COL_TO_BASE_CLASS_MAP_INDEX];
            relCmpdStartIndexMap = maps[DataFactoryUtils.CMPD_START_IDX_MAP_INDEX];

            log.trace("index maps built: baseProviderIndexMap = {}, relColIdxMap = {}",
                    baseProviderIndexMap.toString(), relCmpdStartIndexMap.toString());

            if (baseProviderIndexMap.size() == 0) {
                log.debug("base DataProvider index mapping is invalid - size 0");
                throw new Exception("CompoundDataProvider: invalid DataProvider mapping of size 0 built");
            }

            if (relCmpdStartIndexMap.size() == 0) {
                log.debug("compound field start index mapping is invalid - size 0");
                throw new Exception("CompoundDataProvider: invalid compound field start index mapping of size 0 built");
            }

            /*
             * nCols should represent the number of columns covered by this CompoundDataProvider
             * only. For top-level CompoundDataProviders, this should be the entire width of the
             * dataset. For nested CompoundDataProviders, nCols will be a subset of these columns.
             */
            nCols = (int) compoundFormat.getWidth() * baseProviderIndexMap.size();
            nRows = (int) compoundFormat.getHeight();

            nSubColumns = (int) compoundFormat.getWidth();
        }

        @Override
        public Object getDataValue(int columnIndex, int rowIndex) {
            try {
                int fieldIdx = columnIndex;
                int rowIdx = rowIndex;

                if (nSubColumns > 1) { // multi-dimension compound dataset
                    /*
                     * Make sure fieldIdx is within a valid range, since even for multi-dimensional
                     * compound datasets there will only be as many lists of data as there are
                     * members in a single compound type.
                     */
                    fieldIdx %= selectedMemberTypes.length;

                    int realColIdx = columnIndex / selectedMemberTypes.length;
                    rowIdx = rowIndex * nSubColumns + realColIdx;
                }

                int providerIndex = baseProviderIndexMap.get(fieldIdx);
                Object colValue = ((List<?>) dataBuf).get(providerIndex);
                if (colValue == null)
                    return DataFactoryUtils.nullStr;

                /*
                 * Delegate data retrieval to one of the base DataProviders according to the
                 * index of the relevant compound field.
                 */
                HDFDataProvider base = baseTypeProviders[providerIndex];
                if (base instanceof CompoundDataProvider)
                    /*
                     * Adjust the compound field index by subtracting the starting index of the
                     * nested compound that we are delegating to. When the nested compound's index
                     * map is setup correctly, this adjusted index should map to the correct field
                     * among the nested compound's members.
                     */
                    theValue = base.getDataValue(colValue, fieldIdx - relCmpdStartIndexMap.get(fieldIdx), rowIdx);
                else if (base instanceof ArrayDataProvider) {
                    /*
                     * TODO: quick temporary fix for specific compound of array of compound files.
                     * Transforms the given column index into a relative index from the starting
                     * index of the array of compound field.
                     */
                    int arrCompoundStartIdx = columnIndex;
                    HDFDataProvider theProvider;
                    while (arrCompoundStartIdx >= 0) {
                        try {
                            theProvider = baseTypeProviders[baseProviderIndexMap.get(arrCompoundStartIdx - 1)];
                            if (theProvider != base)
                                break;

                            arrCompoundStartIdx--;
                        }
                        catch (Exception ex) {
                            break;
                        }
                    }

                    int adjustedColIndex = columnIndex - arrCompoundStartIdx;

                    theValue = base.getDataValue(colValue, adjustedColIndex, rowIdx);
                }
                else
                    theValue = base.getDataValue(colValue, rowIdx);
            }
            catch (Exception ex) {
                log.debug("getDataValue({}, {}): failure: ", rowIndex, columnIndex, ex);
                theValue = DataFactoryUtils.errStr;
            }

            log.trace("getDataValue({}, {}): finish", rowIndex, columnIndex);

            return theValue;
        }

        @Override
        public Object getDataValue(Object obj, int columnIndex, int rowIndex) {
            try {
                int providerIndex = baseProviderIndexMap.get(columnIndex);
                Object colValue = ((List<?>) obj).get(providerIndex);
                if (colValue == null)
                    return DataFactoryUtils.nullStr;

                /*
                 * Delegate data retrieval to one of the base DataProviders according to the
                 * index of the relevant compound field.
                 */
                HDFDataProvider base = baseTypeProviders[providerIndex];
                if (base instanceof CompoundDataProvider)
                    /*
                     * Adjust the compound field index by subtracting the starting index of the
                     * nested compound that we are delegating to. When the nested compound's index
                     * map is setup correctly, this adjusted index should map to the correct field
                     * among the nested compound's members.
                     */
                    theValue = base.getDataValue(colValue, columnIndex - relCmpdStartIndexMap.get(columnIndex), rowIndex);
                else if (base instanceof ArrayDataProvider)
                    theValue = base.getDataValue(colValue, columnIndex, rowIndex);
                else
                    theValue = base.getDataValue(colValue, rowIndex);
            }
            catch (Exception ex) {
                log.debug("getDataValue({}, {}): failure: ", rowIndex, columnIndex, ex);
                theValue = DataFactoryUtils.errStr;
            }
            log.trace("getDataValue({})=({}): finish", rowIndex, columnIndex);

            return theValue;
        }

        @Override
        public Object getDataValue(Object obj, int index) {
            throw new UnsupportedOperationException("getDataValue(Object, int) should not be called for CompoundDataProviders");
        }

        @Override
        public void setDataValue(int columnIndex, int rowIndex, Object newValue) {
            if ((newValue == null) || ((newValue = ((String) newValue).trim()) == null)) {
                log.debug("setDataValue({}, {})=({}): cell value not updated; new value is null", rowIndex, columnIndex, newValue);
                return;
            }

            // No need to update if values are the same
            Object oldVal = this.getDataValue(columnIndex, rowIndex);
            if ((oldVal != null) && newValue.equals(oldVal.toString())) {
                log.debug("setDataValue({}, {})=({}): cell value not updated; new value same as old value", rowIndex, columnIndex, newValue);
                return;
            }

            try {
                int fieldIdx = columnIndex;
                int rowIdx = rowIndex;

                if (nSubColumns > 1) { // multi-dimension compound dataset
                    /*
                     * Make sure fieldIdx is within a valid range, since even for multi-dimensional
                     * compound datasets there will only be as many lists of data as there are
                     * members in a single compound type.
                     */
                    fieldIdx %= selectedMemberTypes.length;

                    int realColIdx = columnIndex / selectedMemberTypes.length;
                    rowIdx = rowIndex * nSubColumns + realColIdx;
                }

                int providerIndex = baseProviderIndexMap.get(fieldIdx);
                Object colValue = ((List<?>) dataBuf).get(providerIndex);
                if (colValue == null) {
                    log.debug("setDataValue({}, {})=({}): colValue is null", rowIndex, columnIndex, newValue);
                    return;
                }

                /*
                 * Delegate data setting to one of the base DataProviders according to the index
                 * of the relevant compound field.
                 */
                HDFDataProvider base = baseTypeProviders[providerIndex];
                if (base.isContainerType)
                    /*
                     * Adjust the compound field index by subtracting the starting index of the
                     * nested compound that we are delegating to. When the nested compound's index
                     * map is setup correctly, this adjusted index should map to the correct field
                     * among the nested compound's members.
                     */
                    base.setDataValue(fieldIdx - relCmpdStartIndexMap.get(fieldIdx), rowIdx, colValue, newValue);
                else
                    base.setDataValue(rowIdx, colValue, newValue);

                isValueChanged = true;
            }
            catch (Exception ex) {
                log.debug("setDataValue({}, {})=({}): cell value update failure: ", rowIndex, columnIndex, newValue);
            }
            log.trace("setDataValue({}, {})=({}): finish", rowIndex, columnIndex, newValue);

            /*
             * TODO: throwing error dialogs when something fails?
             *
             * Tools.showError(shell, "Select", "Unable to set new value:\n\n " + ex);
             */
        }

        @Override
        public void setDataValue(int columnIndex, int rowIndex, Object bufObject, Object newValue) {
            try {
                int providerIndex = baseProviderIndexMap.get(columnIndex);
                Object colValue = ((List<?>) bufObject).get(providerIndex);
                if (colValue == null) {
                    log.debug("setDataValue({}, {}, {})=({}): colValue is null", rowIndex, columnIndex, bufObject, newValue);
                    return;
                }

                /*
                 * Delegate data setting to one of the base DataProviders according to the index
                 * of the relevant compound field.
                 */
                HDFDataProvider base = baseTypeProviders[providerIndex];
                if (base.isContainerType)
                    /*
                     * Adjust the compound field index by subtracting the starting index of the
                     * nested compound that we are delegating to. When the nested compound's index
                     * map is setup correctly, this adjusted index should map to the correct field
                     * among the nested compound's members.
                     */
                    base.setDataValue(columnIndex - relCmpdStartIndexMap.get(columnIndex), rowIndex, colValue, newValue);
                else
                    base.setDataValue(rowIndex, colValue, newValue);

                isValueChanged = true;
            }
            catch (Exception ex) {
                log.debug("setDataValue({}, {}, {})=({}): cell value update failure: ", rowIndex, columnIndex, bufObject, newValue, ex);
            }
            log.trace("setDataValue({}, {}, {})=({}): finish", rowIndex, columnIndex, bufObject, newValue);
        }

        @Override
        public void setDataValue(int index, Object bufObject, Object newValue) {
            throw new UnsupportedOperationException("setDataValue(int, Object, Object) should not be called for CompoundDataProviders");
        }

        @Override
        public int getColumnCount() {
            return nCols;
        }

        @Override
        public int getRowCount() {
            return nRows;
        }
    }

    private static class ArrayDataProvider extends HDFDataProvider
    {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ArrayDataProvider.class);

        private final HDFDataProvider baseTypeDataProvider;

        private final Object[]        arrayElements;
        private final long            arraySize;

        private final int             nCols;

        ArrayDataProvider(final Datatype dtype, final Object dataBuf, final boolean dataTransposed) throws Exception {
            super(dtype, dataBuf, dataTransposed);

            Datatype baseType = dtype.getDatatypeBase();

            baseTypeDataProvider = getDataProvider(baseType, dataBuf, dataTransposed);

            if (baseType.isVarStr())
                arraySize = dtype.getArrayDims()[0];
            else if (baseType.isBitField() || baseType.isOpaque())
                arraySize = dtype.getDatatypeSize();
            else
                arraySize = dtype.getDatatypeSize() / baseType.getDatatypeSize();

            arrayElements = new Object[(int) arraySize];

            if (baseTypeDataProvider instanceof CompoundDataProvider)
                nCols = (int) arraySize * ((CompoundDataProvider) baseTypeDataProvider).nCols;
            else
                nCols = super.getColumnCount();
        }

        @Override
        public Object getDataValue(int columnIndex, int rowIndex) {
            try {
                int bufIndex = physicalLocationToBufIndex(rowIndex, columnIndex);

                bufIndex *= arraySize;

                if (baseTypeDataProvider instanceof CompoundDataProvider) {
                    /*
                     * Pass row and column indices down where they will be adjusted.
                     */
                    theValue = retrieveArrayOfCompoundElements(dataBuf, columnIndex, rowIndex);
                }
                else if (baseTypeDataProvider instanceof ArrayDataProvider) {
                    /*
                     * TODO: assign to global arrayElements.
                     */
                    theValue = retrieveArrayOfArrayElements(dataBuf, columnIndex, bufIndex);
                }
                else {
                    /*
                     * TODO: assign to global arrayElements.
                     */
                    theValue = retrieveArrayOfAtomicElements(dataBuf, bufIndex);
                }
            }
            catch (Exception ex) {
                log.debug("getDataValue({}, {}): failure: ", rowIndex, columnIndex, ex);
                theValue = DataFactoryUtils.errStr;
            }

            log.trace("getDataValue({}, {})({}): finish", rowIndex, columnIndex, theValue);

            return theValue;
        }

        @Override
        public Object getDataValue(Object obj, int columnIndex, int rowIndex) {
            try {
                long index = rowIndex * arraySize;

                if (baseTypeDataProvider instanceof CompoundDataProvider) {
                    /*
                     * Pass row and column indices down where they will be adjusted.
                     */
                    theValue = retrieveArrayOfCompoundElements(obj, columnIndex, rowIndex);
                }
                else if (baseTypeDataProvider instanceof ArrayDataProvider) {
                    theValue = retrieveArrayOfArrayElements(obj, columnIndex, (int) index);
                }
                else {
                    theValue = retrieveArrayOfAtomicElements(obj, (int) index);
                }
            }
            catch (Exception ex) {
                log.debug("getDataValue({}, {}): failure: ", rowIndex, columnIndex, ex);
                theValue = DataFactoryUtils.errStr;
            }

            return theValue;
        }

        private Object[] retrieveArrayOfCompoundElements(Object objBuf, int columnIndex, int rowIndex) {
            long adjustedRowIdx = (rowIndex * arraySize * colCount)
                    + (columnIndex / ((CompoundDataProvider) baseTypeDataProvider).baseProviderIndexMap.size());
            long adjustedColIdx = columnIndex % ((CompoundDataProvider) baseTypeDataProvider).baseProviderIndexMap.size();

            /*
             * Since we flatten array of compound types, we only need to return a single
             * value.
             */
            return new Object[] { baseTypeDataProvider.getDataValue(objBuf, (int) adjustedColIdx, (int) adjustedRowIdx) };
        }

        private Object[] retrieveArrayOfArrayElements(Object objBuf, int columnIndex, int startRowIndex) {
            Object[] tempArray = new Object[(int) arraySize];

            for (int i = 0; i < arraySize; i++)
                tempArray[i] = baseTypeDataProvider.getDataValue(objBuf, columnIndex, startRowIndex + i);

            return tempArray;
        }

        private Object[] retrieveArrayOfAtomicElements(Object objBuf, int rowStartIdx) {
            Object[] tempArray = new Object[(int) arraySize];

            for (int i = 0; i < arraySize; i++)
                tempArray[i] = baseTypeDataProvider.getDataValue(objBuf, rowStartIdx + i);

            return tempArray;
        }

        @Override
        public Object getDataValue(Object obj, int index) {
            throw new UnsupportedOperationException("getDataValue(Object, int) should not be called for ArrayDataProviders");
        }

        @Override
        public void setDataValue(int columnIndex, int rowIndex, Object newValue) {
            try {
                int bufIndex = physicalLocationToBufIndex(rowIndex, columnIndex);

                bufIndex *= arraySize;

                updateArrayElements(dataBuf, newValue, columnIndex, bufIndex);
            }
            catch (Exception ex) {
                log.debug("setDataValue({}, {}, {}): cell value update failure: ", rowIndex, columnIndex, newValue, ex);
            }
            log.trace("setDataValue({}, {})=({}): finish", rowIndex, columnIndex, newValue);
        }

        @Override
        public void setDataValue(int columnIndex, int rowIndex, Object bufObject, Object newValue) {
            try {
                long bufIndex = rowIndex * arraySize;

                updateArrayElements(bufObject, newValue, columnIndex, (int) bufIndex);
            }
            catch (Exception ex) {
                log.debug("setDataValue({}, {}, {}, {}): cell value update failure: ", rowIndex, columnIndex, bufObject, newValue, ex);
            }
            log.trace("setDataValue({}, {}, {})=({}): finish", rowIndex, columnIndex, bufObject, newValue);
        }

        @Override
        public void setDataValue(int index, Object bufObject, Object newValue) {
            throw new UnsupportedOperationException("setDataValue(int, Object, Object) should not be called for ArrayDataProviders");
        }

        private void updateArrayElements(Object curBuf, Object newValue, int columnIndex, int bufStartIndex) {
            StringTokenizer st = new StringTokenizer((String) newValue, ",[]");
            if (st.countTokens() < arraySize) {
                /*
                 * TODO:
                 */
                /* Tools.showError(shell, "Select", "Number of data points < " + morder + "."); */
                log.debug("updateArrayElements(): number of data points ({}) < array size {}", st.countTokens(), arraySize);
                log.trace("updateArrayElements({}, {}, {}): finish", curBuf, newValue, bufStartIndex);
                return;
            }

            if (baseTypeDataProvider instanceof CompoundDataProvider)
                updateArrayOfCompoundElements(st, curBuf, columnIndex, bufStartIndex);
            else if (baseTypeDataProvider instanceof ArrayDataProvider)
                updateArrayOfArrayElements(st, curBuf, columnIndex, bufStartIndex);
            else
                updateArrayOfAtomicElements(st, curBuf, bufStartIndex);
        }

        private void updateArrayOfCompoundElements(StringTokenizer tokenizer, Object curBuf, int columnIndex, int bufStartIndex) {
            for (int i = 0; i < arraySize; i++) {
                List<?> cmpdDataList = (List<?>) ((Object[]) curBuf)[i];
                baseTypeDataProvider.setDataValue(columnIndex, bufStartIndex + i, cmpdDataList,
                        tokenizer.nextToken().trim());
                isValueChanged = isValueChanged || baseTypeDataProvider.getIsValueChanged();
            }
        }

        private void updateArrayOfArrayElements(StringTokenizer tokenizer, Object curBuf, int columnIndex, int bufStartIndex) {
            for (int i = 0; i < arraySize; i++) {
                /*
                 * TODO: not quite right.
                 */
                baseTypeDataProvider.setDataValue(columnIndex, bufStartIndex + i, curBuf, tokenizer.nextToken().trim());
                isValueChanged = isValueChanged || baseTypeDataProvider.getIsValueChanged();
            }
        }

        private void updateArrayOfAtomicElements(StringTokenizer tokenizer, Object curBuf, int bufStartIndex) {
            for (int i = 0; i < arraySize; i++) {
                baseTypeDataProvider.setDataValue(bufStartIndex + i, curBuf, tokenizer.nextToken().trim());
                isValueChanged = isValueChanged || baseTypeDataProvider.getIsValueChanged();
            }
        }

        @Override
        public int getColumnCount() {
            return nCols;
        }
    }

    private static class VlenDataProvider extends HDFDataProvider
    {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VlenDataProvider.class);

        private final HDFDataProvider baseTypeDataProvider;

        private final StringBuilder   buffer;

        VlenDataProvider(final Datatype dtype, final Object dataBuf, final boolean dataTransposed) throws Exception {
            super(dtype, dataBuf, dataTransposed);

            Datatype baseType = dtype.getDatatypeBase();

            baseTypeDataProvider = getDataProvider(baseType, dataBuf, dataTransposed);

            buffer = new StringBuilder();
        }

        @Override
        public Object getDataValue(int columnIndex, int rowIndex) {
            buffer.setLength(0);

            try {
                int bufIndex = physicalLocationToBufIndex(rowIndex, columnIndex);

                if (baseTypeDataProvider instanceof CompoundDataProvider) {
                    /*
                     * TODO:
                     */
                    /*
                     * buffer.append(baseTypeDataProvider.getDataValue(dataBuf, columnIndex, (int) index));
                     */
                    if (dataBuf instanceof String[])
                        buffer.append(Array.get(dataBuf, bufIndex));
                    else
                        buffer.append("*UNSUPPORTED*");
                }
                else if (baseTypeDataProvider instanceof ArrayDataProvider) {
                    buffer.append(baseTypeDataProvider.getDataValue(dataBuf, columnIndex, bufIndex));
                }
                else {
                    buffer.append(baseTypeDataProvider.getDataValue(dataBuf, bufIndex));
                }

                theValue = buffer.toString();
            }
            catch (Exception ex) {
                log.debug("getDataValue({}, {}): failure: ", rowIndex, columnIndex, ex);
                theValue = DataFactoryUtils.errStr;
            }

            log.trace("getDataValue({}, {})=({}): finish", rowIndex, columnIndex, theValue);

            return theValue;
        }

        @Override
        public Object getDataValue(Object obj, int columnIndex, int rowIndex) {
            buffer.setLength(0);

            try {
                if (baseTypeDataProvider instanceof CompoundDataProvider) {
                    /*
                     * TODO:
                     */
                    /*
                     * buffer.append(baseTypeDataProvider.getDataValue(obj, columnIndex, rowIndex));
                     */
                    if (obj instanceof String[])
                        buffer.append(Array.get(obj, rowIndex));
                    else
                        buffer.append("*UNSUPPORTED*");
                }
                else if (baseTypeDataProvider instanceof ArrayDataProvider) {
                    buffer.append(baseTypeDataProvider.getDataValue(obj, columnIndex, rowIndex));
                }
                else {
                    buffer.append(baseTypeDataProvider.getDataValue(obj, rowIndex));
                }

                theValue = buffer.toString();
            }
            catch (Exception ex) {
                log.debug("getDataValue({}, {}): failure: ", rowIndex, columnIndex, ex);
                theValue = DataFactoryUtils.errStr;
            }

            log.trace("getDataValue({}, {}, {})=({}): finish", obj, rowIndex, columnIndex, theValue);

            return theValue;
        }

        /* @Override
        public Object getDataValue(Object obj, int index) {
            throw new UnsupportedOperationException("getDataValue(Object, int) should not be called for VlenDataProviders");
        } */

        @Override
        public void setDataValue(int columnIndex, int rowIndex, Object newValue) {
            /*
             * TODO:
             */
        }

        @Override
        public void setDataValue(int columnIndex, int rowIndex, Object bufObject, Object newValue) {
            /*
             * TODO:
             */
        }

        @Override
        public void setDataValue(int index, Object bufObject, Object newValue) {
            throw new UnsupportedOperationException("setDataValue(int, Object, Object) should not be called for VlenDataProviders");
        }
    }

    private static class StringDataProvider extends HDFDataProvider
    {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StringDataProvider.class);

        private final long typeSize;

        StringDataProvider(final Datatype dtype, final Object dataBuf, final boolean dataTransposed) throws Exception {
            super(dtype, dataBuf, dataTransposed);

            typeSize = dtype.getDatatypeSize();
        }

        @Override
        public Object getDataValue(Object obj, int index) {
            if (obj instanceof byte[]) {
                int strlen = (int) typeSize;

                log.trace("getDataValue({}, {}): converting byte[] to String", obj, index);

                String str = new String((byte[]) obj, index * strlen, strlen);
                int idx = str.indexOf('\0');
                if (idx > 0)
                    str = str.substring(0, idx);

                theValue = str.trim();
            }
            else
                super.getDataValue(obj, index);

            log.trace("getDataValue({}, {})=({}): finish", obj, index, theValue);

            return theValue;
        }

        @Override
        public void setDataValue(int columnIndex, int rowIndex, Object newValue) {
            try {
                int bufIndex = physicalLocationToBufIndex(rowIndex, columnIndex);

                updateStringBytes(dataBuf, newValue, bufIndex);
            }
            catch (Exception ex) {
                log.debug("setDataValue({}, {}, {}): cell value update failure: ", rowIndex, columnIndex, newValue, ex);
            }
            log.trace("setDataValue({}, {}, {}): finish", rowIndex, columnIndex, newValue);
        }

        @Override
        public void setDataValue(int index, Object bufObject, Object newValue) {
            try {
                updateStringBytes(bufObject, newValue, index);
            }
            catch (Exception ex) {
                log.debug("setDataValue({}, {}, {}): cell value update failure: ", index, bufObject, newValue, ex);
            }
            log.trace("setDataValue({}, {}, {}): finish", index, bufObject, newValue);
        }

        private void updateStringBytes(Object curBuf, Object newValue, int bufStartIndex) {
            if (curBuf instanceof String[]) {
                Array.set(curBuf, bufStartIndex, newValue);
            }
            else if (curBuf instanceof byte[]) {
                // Update String using data represented as a byte[]
                int strLen = (int) typeSize;
                byte[] newValueBytes = ((String) newValue).getBytes();
                byte[] curBytes = (byte[]) curBuf;
                int n = Math.min(strLen, newValueBytes.length);

                bufStartIndex *= typeSize;

                System.arraycopy(newValueBytes, 0, curBytes, bufStartIndex, n);

                bufStartIndex += n;
                n = strLen - newValueBytes.length;

                // space padding
                for (int i = 0; i < n; i++)
                    curBytes[bufStartIndex + i] = ' ';
            }

            isValueChanged = true;
        }
    }

    private static class CharDataProvider extends HDFDataProvider
    {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CharDataProvider.class);

        CharDataProvider(final Datatype dtype, final Object dataBuf, final boolean dataTransposed) throws Exception {
            super(dtype, dataBuf, dataTransposed);
        }

        @Override
        public Object getDataValue(int columnIndex, int rowIndex) {
            /*
             * Compatibility with HDF4 8-bit character types that get converted to a String
             * ahead of time.
             */
            if (dataBuf instanceof String) {
                log.trace("getDataValue({}, {})=({}): finish", rowIndex, columnIndex, dataBuf);
                return dataBuf;
            }

            return super.getDataValue(columnIndex, rowIndex);
        }
    }

    private static class NumericalDataProvider extends HDFDataProvider
    {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NumericalDataProvider.class);

        private final boolean isUINT64;

        private final long    typeSize;

        NumericalDataProvider(final Datatype dtype, final Object dataBuf, final boolean dataTransposed) throws Exception {
            super(dtype, dataBuf, dataTransposed);

            typeSize = dtype.getDatatypeSize();
            isUINT64 = dtype.isUnsigned() && (typeSize == 8);
        }

        @Override
        public Object getDataValue(int columnIndex, int rowIndex) {
            super.getDataValue(columnIndex, rowIndex);

            try {
                if (isUINT64)
                    theValue = Tools.convertUINT64toBigInt(Long.valueOf((long) theValue));
            }
            catch (Exception ex) {
                log.debug("getDataValue({}, {}): failure: ", rowIndex, columnIndex, ex);
                theValue = DataFactoryUtils.errStr;
            }

            log.trace("getDataValue({}, {})=({}): finish", rowIndex, columnIndex, theValue);

            return theValue;
        }

        @Override
        public Object getDataValue(Object obj, int index) {
            super.getDataValue(obj, index);

            try {
                if (isUINT64)
                    theValue = Tools.convertUINT64toBigInt(Long.valueOf((long) theValue));
            }
            catch (Exception ex) {
                log.debug("getDataValue({}): failure: ", index, ex);
                theValue = DataFactoryUtils.errStr;
            }

            log.trace("getDataValue({})=({}): finish", index, theValue);

            return theValue;
        }
    }

    private static class EnumDataProvider extends HDFDataProvider
    {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EnumDataProvider.class);

        EnumDataProvider(final Datatype dtype, final Object dataBuf, final boolean dataTransposed) throws Exception {
            super(dtype, dataBuf, dataTransposed);
        }
    }

    private static class BitfieldDataProvider extends HDFDataProvider
    {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BitfieldDataProvider.class);

        private final long typeSize;

        BitfieldDataProvider(final Datatype dtype, final Object dataBuf, final boolean dataTransposed) throws Exception {
            super(dtype, dataBuf, dataTransposed);

            typeSize = dtype.getDatatypeSize();
        }

        @Override
        public Object getDataValue(int columnIndex, int rowIndex) {
            try {
                int bufIndex = physicalLocationToBufIndex(rowIndex, columnIndex);

                bufIndex *= typeSize;
                theValue = populateByteArray(dataBuf, bufIndex);
            }
            catch (Exception ex) {
                log.debug("getDataValue({}, {}): failure: ", rowIndex, columnIndex, ex);
                theValue = DataFactoryUtils.errStr;
            }

            log.trace("getDataValue({}, {})=({}): finish", rowIndex, columnIndex, theValue);

            return theValue;
        }

        @Override
        public Object getDataValue(Object obj, int index) {
            try {
                index *= typeSize;
                theValue = populateByteArray(obj, index);
            }
            catch (Exception ex) {
                log.debug("getDataValue({}): ", index, ex);
                theValue = DataFactoryUtils.errStr;
            }

            log.trace("getDataValue({})=({}): finish", index, theValue);

            return theValue;
        }

        private byte[] populateByteArray(Object byteBuf, int startIndex) {
            byte[] byteElements = new byte[(int) typeSize];

            for (int i = 0; i < typeSize; i++)
                byteElements[i] = Array.getByte(byteBuf, startIndex + i);

            return byteElements;
        }
    }

    private static class RefDataProvider extends HDFDataProvider
    {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RefDataProvider.class);

        private final long typeSize;
        private final H5ReferenceType h5dtype;

        RefDataProvider(final Datatype dtype, final Object dataBuf, final boolean dataTransposed) throws Exception {
            super(dtype, dataBuf, dataTransposed);

            h5dtype = (H5ReferenceType)dtype;
            typeSize = dtype.getDatatypeSize();
        }

        @Override
        public Object getDataValue(int columnIndex, int rowIndex) {
            log.trace("getDataValue({}, {}): start", rowIndex, columnIndex);

            try {
                int bufIndex = physicalLocationToBufIndex(rowIndex, columnIndex);

                bufIndex *= typeSize;
                theValue = populateReferenceRegion(dataBuf, bufIndex);
            }
            catch (Exception ex) {
                log.debug("getDataValue({}, {}): failure: ", rowIndex, columnIndex, ex);
                theValue = DataFactoryUtils.errStr;
            }

            log.trace("getDataValue({}, {})({}): finish", rowIndex, columnIndex, theValue);

            return theValue;
        }

        @Override
        public Object getDataValue(Object obj, int index) {
            log.trace("getDataValue(Object:{}, {}): start", obj, index);

            try {
                index *= typeSize;
                theValue = populateReferenceRegion(obj, index);
            }
            catch (Exception ex) {
                log.debug("getDataValueObject:({}, {}): ", obj, index, ex);
                theValue = DataFactoryUtils.errStr;
            }

            log.trace("getDataValue(Object:{}, {})({}): finish", obj, index, theValue);

            return theValue;
        }

        private String populateReferenceRegion(Object byteBuf, int startIndex) {
            byte[] byteElements = new byte[(int) typeSize];
            String regionStr = null;

            for (int i = 0; i < typeSize; i++) {
                byteElements[i] = Array.getByte(byteBuf, startIndex + i);
            }
            log.trace("populateReferenceRegion byteElements={}:", byteElements);
            regionStr = h5dtype.getReferenceRegion(byteElements, false);
            log.trace("populateReferenceRegion regionStr={}:", regionStr);

            return regionStr;
        }

    }

    /**
     * Since compound type attributes are currently simply retrieved as a 1D array
     * of strings, we use a custom IDataProvider to provide data for the Compound
     * TableView from the array of strings.
     */
    /*
     * TODO: Update after making compound attributes be read as real data instead of
     * strings.
     */
    private static class CompoundAttributeDataProvider extends HDFDataProvider {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompoundAttributeDataProvider.class);

        private Object              theAttrValue;

        private final StringBuilder stringBuffer;

        private final int           orders[];
        private final int           nFields;
        private final int           nRows;
        private final int           nCols;
        private final int           nSubColumns;

        CompoundAttributeDataProvider(final Datatype dtype, final Object dataBuf, final boolean dataTransposed) throws Exception {
            super(dtype, dataBuf, dataTransposed);

            CompoundDataFormat dataFormat = (CompoundDataFormat) dataFormatReference;

            stringBuffer = new StringBuilder();

            orders = dataFormat.getSelectedMemberOrders();
            nFields = dataFormat.getSelectedMemberCount();
            nRows = (int) dataFormat.getHeight();
            nCols = (int) (dataFormat.getWidth() * dataFormat.getSelectedMemberCount());
            nSubColumns = (nFields > 0) ? getColumnCount() / nFields : 0;
        }

        @Override
        public Object getDataValue(int columnIndex, int rowIndex) {
            try {
                int fieldIdx = columnIndex;
                int rowIdx = rowIndex;

                log.trace("CompoundAttributeDataProvider:getDataValue({},{}) start", rowIndex, columnIndex);

                if (nSubColumns > 1) { // multi-dimension compound dataset
                    int colIdx = columnIndex / nFields;
                    fieldIdx %= nFields;
                    rowIdx = rowIndex * orders[fieldIdx] * nSubColumns + colIdx * orders[fieldIdx];
                    log.trace(
                            "CompoundAttributeDataProvider:getDataValue() row={} orders[{}]={} nSubColumns={} colIdx={}",
                            rowIndex, fieldIdx, orders[fieldIdx], nSubColumns, colIdx);
                }
                else {
                    rowIdx = rowIndex * orders[fieldIdx];
                    log.trace("CompoundAttributeDataProvider:getDataValue() row={} orders[{}]={}", rowIndex, fieldIdx,
                            orders[fieldIdx]);
                }

                rowIdx = rowIndex;

                log.trace("CompoundAttributeDataProvider:getDataValue() rowIdx={}", rowIdx);

                int listIndex = ((columnIndex + (rowIndex * nCols)) / nFields);
                String colValue = (String) ((List<?>) dataBuf).get(listIndex);
                if (colValue == null) {
                    return "null";
                }

                colValue = colValue.replace("{", "").replace("}", "");
                colValue = colValue.replace("[", "").replace("]", "");

                String[] dataValues = colValue.split(",");
                if (orders[fieldIdx] > 1) {
                    stringBuffer.setLength(0);

                    stringBuffer.append("[");

                    int startIdx = 0;
                    for (int i = 0; i < fieldIdx; i++) {
                        startIdx += orders[i];
                    }

                    for (int i = 0; i < orders[fieldIdx]; i++) {
                        if (i > 0) stringBuffer.append(", ");

                        stringBuffer.append(dataValues[startIdx + i]);
                    }

                    stringBuffer.append("]");

                    theAttrValue = stringBuffer.toString();
                }
                else {
                    theAttrValue = dataValues[fieldIdx];
                }
            }
            catch (Exception ex) {
                log.debug("CompoundAttributeDataProvider:getDataValue({}, {}) failure: ", rowIndex, columnIndex, ex);
                theAttrValue = DataFactoryUtils.errStr;
            }

            return theAttrValue;
        }

        @Override
        public void setDataValue(int columnIndex, int rowIndex, Object newValue) {
            log.trace("CompoundAttributeDataProvider: setDataValue({}, {})({}) start", rowIndex, columnIndex, newValue);

            super.setDataValue(columnIndex, rowIndex, newValue);

            log.trace("CompoundAttributeDataProvider: setDataValue({}, {})({}) finish", rowIndex, columnIndex, newValue);
        }

        @Override
        public int getColumnCount() {
            return nCols;
        }

        @Override
        public int getRowCount() {
            return nRows;
        }

    }

}
