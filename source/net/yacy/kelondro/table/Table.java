// Table.java
// (C) 2008 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 14.01.2008 on http://yacy.net
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
//
// LICENSE
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package net.yacy.kelondro.table;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import net.yacy.kelondro.index.Column;
import net.yacy.kelondro.index.HandleMap;
import net.yacy.kelondro.index.Index;
import net.yacy.kelondro.index.Row;
import net.yacy.kelondro.index.Row.Entry;
import net.yacy.kelondro.index.RowCollection;
import net.yacy.kelondro.index.RowSet;
import net.yacy.kelondro.index.RowSpaceExceededException;
import net.yacy.kelondro.io.BufferedRecords;
import net.yacy.kelondro.io.Records;
import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.order.CloneableIterator;
import net.yacy.kelondro.order.NaturalOrder;
import net.yacy.kelondro.util.FileUtils;
import net.yacy.kelondro.util.MemoryControl;
import net.yacy.kelondro.util.kelondroException;


/*
 * The Table builds upon the EcoFS and tries to reduce the number of IO requests that the
 * EcoFS must do to a minimum. In best cases, no IO has to be done for read operations (complete database shadow in RAM)
 * and a rare number of write IO operations must be done for a large number of table-writings (using the write buffer of EcoFS)
 * To make the Table scalable in question of available RAM, there are two elements that must be scalable:
 * - the access index can be either completely in RAM (kelondroRAMIndex) or it is file-based (kelondroTree)
 * - the content cache can be either a complete RAM-based shadow of the File, or empty.
 * The content cache can also be deleted during run-time, if the available RAM gets too low.
 */

public class Table implements Index, Iterable<Row.Entry> {

    // static tracker objects
    private final static TreeMap<String, Table> tableTracker = new TreeMap<String, Table>();
    private final static long maxarraylength = 134217727L; // that may be the maximum size of array length in some JVMs

    private final long minmemremaining; // if less than this memory is remaininig, the memory copy of a table is abandoned
    private final int buffersize;
    private final Row rowdef;
    private final Row taildef;
    private       HandleMap index;
    private       BufferedRecords file;
    private       RowSet table;

    public Table(
    		final File tablefile,
    		final Row rowdef,
    		final int buffersize,
    		final int initialSpace,
    		boolean useTailCache,
    		final boolean exceed134217727) throws RowSpaceExceededException {
        useTailCache = true; // fixed for testing

        this.rowdef = rowdef;
        this.buffersize = buffersize;
        this.minmemremaining = Math.max(400 * 1024 * 1024, MemoryControl.available() / 10);
        //this.fail = 0;
        // define the taildef, a row like the rowdef but without the first column
        final Column[] cols = new Column[rowdef.columns() - 1];
        for (int i = 0; i < cols.length; i++) {
            cols[i] = rowdef.column(i + 1);
        }
        this.taildef = new Row(cols, NaturalOrder.naturalOrder);

        // initialize table file
        boolean freshFile = false;
        if (!tablefile.exists()) {
            // make new file
            freshFile = true;
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(tablefile);
            } catch (final FileNotFoundException e) {
                // should not happen
                Log.logSevere("Table", "", e);
            }
            if (fos != null) try { fos.close(); } catch (final IOException e) {}
        }

        try {
            // open an existing table file
            final int fileSize = (int) tableSize(tablefile, rowdef.objectsize, true);

            // initialize index and copy table
            final int  records = Math.max(fileSize, initialSpace);
            final long neededRAM4table = (records) * ((rowdef.objectsize) + 4L) * 3L;
            this.table = ((exceed134217727 || neededRAM4table < maxarraylength) &&
                     (useTailCache && MemoryControl.available() > neededRAM4table + 200 * 1024 * 1024)) ?
                    new RowSet(this.taildef, records) : null;
            Log.logInfo("TABLE", "initialization of " + tablefile.getName() + ". table copy: " + ((this.table == null) ? "no" : "yes") + ", available RAM: " + (MemoryControl.available() / 1024 / 1024) + "MB, needed: " + (neededRAM4table/1024/1024 + 200) + "MB, allocating space for " + records + " entries");
            final long neededRAM4index = 400 * 1024 * 1024 + records * (rowdef.primaryKeyLength + 4) * 3 / 2;
            if (!MemoryControl.request(neededRAM4index, false)) {
                // despite calculations seemed to show that there is enough memory for the table AND the index
                // there is now not enough memory left for the index. So delete the table again to free the memory
                // for the index
                Log.logSevere("TABLE", tablefile.getName() + ": not enough RAM (" + (MemoryControl.available() / 1024 / 1024) + "MB) left for index, deleting allocated table space to enable index space allocation (needed: " + (neededRAM4index / 1024 / 1024) + "MB)");
                this.table = null; System.gc();
                Log.logSevere("TABLE", tablefile.getName() + ": RAM after releasing the table: " + (MemoryControl.available() / 1024 / 1024) + "MB");
            }
            this.index = new HandleMap(rowdef.primaryKeyLength, rowdef.objectOrder, 4, records, tablefile.getAbsolutePath());
            final HandleMap errors = new HandleMap(rowdef.primaryKeyLength, NaturalOrder.naturalOrder, 4, records, tablefile.getAbsolutePath() + ".errors");
            Log.logInfo("TABLE", tablefile + ": TABLE " + tablefile.toString() + " has table copy " + ((this.table == null) ? "DISABLED" : "ENABLED"));

            // read all elements from the file into the copy table
            Log.logInfo("TABLE", "initializing RAM index for TABLE " + tablefile.getName() + ", please wait.");
            int i = 0;
            byte[] key;
            if (this.table == null) {
                final Iterator<byte[]> ki = new ChunkIterator(tablefile, rowdef.objectsize, rowdef.primaryKeyLength);
                while (ki.hasNext()) {
                    key = ki.next();
                    // write the key into the index table
                    assert key != null;
                    if (key == null) {i++; continue;}
                    if (rowdef.objectOrder.wellformed(key)) {
                        this.index.putUnique(key, i++);
                    } else {
                        errors.putUnique(key, i++);
                    }
                }
            } else {
                byte[] record;
                key = new byte[rowdef.primaryKeyLength];
                final Iterator<byte[]> ri = new ChunkIterator(tablefile, rowdef.objectsize, rowdef.objectsize);
                while (ri.hasNext()) {
                    record = ri.next();
                    assert record != null;
                    if (record == null) {i++; continue;}
                    System.arraycopy(record, 0, key, 0, rowdef.primaryKeyLength);

                    // write the key into the index table
                    if (rowdef.objectOrder.wellformed(key)) {
                        this.index.putUnique(key, i++);
                        // write the tail into the table
                        try {
                            this.table.addUnique(this.taildef.newEntry(record, rowdef.primaryKeyLength, true));
                        } catch (final RowSpaceExceededException e) {
                            this.table = null;
                            break;
                        }
                        if (abandonTable()) {
                            this.table = null;
                            break;
                        }
                    } else {
                        errors.putUnique(key, i++);
                    }
                }
            }

            // open the file
            this.file = new BufferedRecords(new Records(tablefile, rowdef.objectsize), this.buffersize);
            assert this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size() + ", file = " + filename();

            // clean up the file by cleaning badly formed entries
            final int errorc = errors.size();
            int errorcc = 0;
            int idx;
            for (final Entry entry: errors) {
                key = entry.getPrimaryKeyBytes();
                idx = (int) entry.getColLong(1);
                Log.logWarning("Table", "removing not well-formed entry " + idx + " with key: " + NaturalOrder.arrayList(key, 0, key.length) + ", " + errorcc++ + "/" + errorc);
                removeInFile(idx);
            }
            errors.close();
            assert this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size() + ", file = " + filename();

            // remove doubles
            if (!freshFile) {
                final ArrayList<long[]> doubles = this.index.removeDoubles();
                //assert index.size() + doubles.size() + fail == i;
                //System.out.println(" -removed " + doubles.size() + " doubles- done.");
                if (!doubles.isEmpty()) {
                    Log.logInfo("TABLE", tablefile + ": WARNING - TABLE " + tablefile + " has " + doubles.size() + " doubles");
                    // from all the doubles take one, put it back to the index and remove the others from the file
                    // first put back one element each
                    final byte[] record = new byte[rowdef.objectsize];
                    key = new byte[rowdef.primaryKeyLength];
                    for (final long[] ds: doubles) {
                        this.file.get((int) ds[0], record, 0);
                        System.arraycopy(record, 0, key, 0, rowdef.primaryKeyLength);
                        this.index.putUnique(key, (int) ds[0]);
                    }
                    // then remove the other doubles by removing them from the table, but do a re-indexing while doing that
                    // first aggregate all the delete positions because the elements from the top positions must be removed first
                    final TreeSet<Long> delpos = new TreeSet<Long>();
                    for (final long[] ds: doubles) {
                        for (int j = 1; j < ds.length; j++) delpos.add(ds[j]);
                    }
                    // now remove the entries in a sorted way (top-down)
                    Long top;
                    while (!delpos.isEmpty()) {
                        top = delpos.last();
                        delpos.remove(top);
                        removeInFile(top.intValue());
                    }
                }
            }
        } catch (final FileNotFoundException e) {
            // should never happen
            Log.logSevere("Table", "", e);
            throw new kelondroException(e.getMessage());
        } catch (final IOException e) {
            Log.logSevere("Table", "", e);
            throw new kelondroException(e.getMessage());
        }

        // track this table
        tableTracker.put(tablefile.toString(), this);
    }

    public long mem() {
        return this.index.mem() + ((this.table == null) ? 0 : this.table.mem());
    }

    private boolean abandonTable() {
        // check if not enough memory is there to maintain a memory copy of the table
        return MemoryControl.shortStatus() || MemoryControl.available() < this.minmemremaining;
    }

    public byte[] smallestKey() {
        return this.index.smallestKey();
    }

    public byte[] largestKey() {
        return this.index.largestKey();
    }

    public static long tableSize(final File tablefile, final int recordsize, final boolean fixIfCorrupted) throws kelondroException {
        try {
            return Records.tableSize(tablefile, recordsize);
        } catch (final IOException e) {
            if (!fixIfCorrupted) {
                Log.logSevere("Table", "table size broken for file " + tablefile.toString(), e);
                throw new kelondroException(e.getMessage());
            }
            Log.logSevere("Table", "table size broken, try to fix " + tablefile.toString());
            try {
                Records.fixTableSize(tablefile, recordsize);
                Log.logInfo("Table", "successfully fixed table file " + tablefile.toString());
                return Records.tableSize(tablefile, recordsize);
            } catch (final IOException ee) {
                Log.logSevere("Table", "table size fix did not work", ee);
                throw new kelondroException(e.getMessage());
            }
        }
    }

    public static final Iterator<String> filenames() {
        // iterates string objects; all file names from record tracker
        return tableTracker.keySet().iterator();
    }

    public static final Map<String, String> memoryStats(final String filename) {
        // returns a map for each file in the tracker;
        // the map represents properties for each record objects,
        // i.e. for cache memory allocation
        final Table theTABLE = tableTracker.get(filename);
        return theTABLE.memoryStats();
    }

    private final Map<String, String> memoryStats() {
        // returns statistical data about this object
        synchronized (this) {
            assert this.table == null || this.table.size() == this.index.size() : "table.size() = " + this.table.size() + ", index.size() = " + this.index.size();
        }
        final HashMap<String, String> map = new HashMap<String, String>(8);
        if (this.index == null) return map; // possibly closed or beeing closed
        map.put("tableSize", Integer.toString(this.index.size()));
        map.put("tableKeyChunkSize", Integer.toString(this.index.row().objectsize));
        map.put("tableKeyMem", Integer.toString(this.index.row().objectsize * this.index.size()));
        map.put("tableValueChunkSize", (this.table == null) ? "0" : Integer.toString(this.table.row().objectsize));
        map.put("tableValueMem", (this.table == null) ? "0" : Integer.toString(this.table.row().objectsize * this.table.size()));
        return map;
    }

    public boolean usesFullCopy() {
        return this.table != null;
    }

    public static long staticRAMIndexNeed(final File f, final Row rowdef) {
        return (((rowdef.primaryKeyLength + 4)) * tableSize(f, rowdef.objectsize, true) * RowCollection.growfactorLarge100 / 100L);
    }

    public boolean consistencyCheck() {
        try {
            return this.file.size() == this.index.size();
        } catch (final IOException e) {
            Log.logException(e);
            return false;
        }
    }

    public synchronized void addUnique(final Entry row) throws IOException, RowSpaceExceededException {
        assert this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size();
        assert this.table == null || this.table.size() == this.index.size() : "table.size() = " + this.table.size() + ", index.size() = " + this.index.size();
        final int i = (int) this.file.size();
        try {
            this.index.putUnique(row.getPrimaryKeyBytes(), i);
        } catch (final RowSpaceExceededException e) {
            if (this.table == null) throw e; // in case the table is not used, there is no help here
            this.table = null;
            // try again with less memory
            this.index.putUnique(row.getPrimaryKeyBytes(), i);
        }
        if (this.table != null) {
            assert this.table.size() == i;
            try {
                this.table.addUnique(this.taildef.newEntry(row.bytes(), this.rowdef.primaryKeyLength, true));
            } catch (final RowSpaceExceededException e) {
                this.table = null;
            }
            if (abandonTable()) this.table = null;
        }
        this.file.add(row.bytes(), 0);
        assert this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size();
    }

    public synchronized void addUnique(final List<Entry> rows) throws IOException, RowSpaceExceededException {
        assert this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size();
        for (final Entry entry: rows) {
            try {
                addUnique(entry);
            } catch (final RowSpaceExceededException e) {
                if (this.table == null) throw e;
                this.table = null;
                addUnique(entry);
            }
        }
        assert this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size();
    }

    /**
     * @throws RowSpaceExceededException
     * remove double-entries from the table
     * this process calls the underlying removeDoubles() method from the table index
     * and
     * @throws
     */
    public synchronized List<RowCollection> removeDoubles() throws IOException, RowSpaceExceededException {
        assert this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size();
        final List<RowCollection> report = new ArrayList<RowCollection>();
        RowSet rows;
        final TreeSet<Long> d = new TreeSet<Long>();
        final byte[] b = new byte[this.rowdef.objectsize];
        Row.Entry inconsistentEntry;
        // iterate over all entries that have inconsistent index references
        long lastlog = System.currentTimeMillis();
        List<long[]> doubles;
        try {
            doubles = this.index.removeDoubles();
        } catch (final RowSpaceExceededException e) {
            if (this.table == null) throw e;
            this.table = null;
            doubles = this.index.removeDoubles();
        }
        for (final long[] is: doubles) {
            // 'is' is the set of all indexes, that have the same reference
            // we collect that entries now here
            rows = new RowSet(this.rowdef, is.length);
            for (final long L : is) {
                assert (int) L < this.file.size() : "L.intValue() = " + (int) L + ", file.size = " + this.file.size(); // prevent ooBounds Exception
                d.add(L);
                if ((int) L >= this.file.size()) continue; // prevent IndexOutOfBoundsException
                this.file.get((int) L, b, 0); // TODO: fix IndexOutOfBoundsException here
                inconsistentEntry = this.rowdef.newEntry(b);
                try {
                    rows.addUnique(inconsistentEntry);
                } catch (final RowSpaceExceededException e) {
                    if (this.table == null) throw e;
                    this.table = null;
                    rows.addUnique(inconsistentEntry);
                }
            }
            report.add(rows);
        }
        // finally delete the affected rows, but start with largest id first, otherwise we overwrite wrong entries
        Long s;
        while (!d.isEmpty()) {
            s = d.last();
            d.remove(s);
            removeInFile(s.intValue());
            if (System.currentTimeMillis() - lastlog > 30000) {
                Log.logInfo("TABLE", "removing " + d.size() + " entries in " + filename());
                lastlog = System.currentTimeMillis();
            }
        }
        assert this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size();
        return report;
    }

    public void close() {
        if (this.file != null) this.file.close();
        this.file = null;
        if (this.table != null) this.table.close();
        this.table = null;
        if (this.index != null) this.index.close();
        this.index = null;
    }

    @Override
    protected void finalize() {
        if (this.file != null) close();
    }

    public String filename() {
        return this.file.filename().toString();
    }

    public Entry get(final byte[] key, final boolean _forcecopy) throws IOException {
        if (this.file == null || this.index == null) return null;
        Entry e = get0(key);
        if (e != null && this.rowdef.objectOrder.equal(key, e.getPrimaryKeyBytes())) return e;
        synchronized (this) {
            assert this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size() + ", file = " + filename();
            assert this.table == null || this.table.size() == this.index.size() : "table.size() = " + this.table.size() + ", index.size() = " + this.index.size() + ", file = " + filename();
            e = get0(key);
            assert e == null || this.rowdef.objectOrder.equal(key, e.getPrimaryKeyBytes());
            return e;
        }
    }

    private Entry get0(final byte[] key) throws IOException {
    	if (this.file == null || this.index == null) return null;
        final int i = (int) this.index.get(key);
        if (i == -1) return null;
        final byte[] b = new byte[this.rowdef.objectsize];
        final Row.Entry cacherow;
        if (this.table == null || (cacherow = this.table.get(i, false)) == null) {
            // read row from the file
            this.file.get(i, b, 0);
        } else {
            // construct the row using the copy in RAM
            assert cacherow != null;
            if (cacherow == null) return null;
            assert key.length == this.rowdef.primaryKeyLength;
            System.arraycopy(key, 0, b, 0, key.length);
            System.arraycopy(cacherow.bytes(), 0, b, this.rowdef.primaryKeyLength, this.rowdef.objectsize - this.rowdef.primaryKeyLength);
        }
        return this.rowdef.newEntry(b);
    }

    public Map<byte[], Row.Entry> get(final Collection<byte[]> keys, final boolean forcecopy) throws IOException, InterruptedException {
        final Map<byte[], Row.Entry> map = new TreeMap<byte[], Row.Entry>(row().objectOrder);
        Row.Entry entry;
        for (final byte[] key: keys) {
            entry = get(key, forcecopy);
            if (entry != null) map.put(key, entry);
        }
        return map;
    }

    public boolean has(final byte[] key) {
        if (this.index == null) return false;
        return this.index.has(key);
    }

    public synchronized CloneableIterator<byte[]> keys(final boolean up, final byte[] firstKey) throws IOException {
        return this.index.keys(up, firstKey);
    }

    public synchronized Entry replace(final Entry row) throws IOException, RowSpaceExceededException {
        assert this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size();
        assert this.table == null || this.table.size() == this.index.size() : "table.size() = " + this.table.size() + ", index.size() = " + this.index.size();
        assert row != null;
        assert row.bytes() != null;
        if (row == null || row.bytes() == null) return null;
        final int i = (int) this.index.get(row.getPrimaryKeyBytes());
        if (i == -1) {
            try {
                addUnique(row);
            } catch (final RowSpaceExceededException e) {
                if (this.table == null) throw e;
                this.table = null;
                addUnique(row);
            }
            return null;
        }

        final byte[] b = new byte[this.rowdef.objectsize];
        Row.Entry cacherow;
        if (this.table == null || (cacherow = this.table.get(i, false)) == null) {
            // read old value
            this.file.get(i, b, 0);
            // write new value
            this.file.put(i, row.bytes(), 0);
        } else {
            // read old value
            assert cacherow != null;
            System.arraycopy(row.getPrimaryKeyBytes(), 0, b, 0, this.rowdef.primaryKeyLength);
            System.arraycopy(cacherow.bytes(), 0, b, this.rowdef.primaryKeyLength, this.rowdef.objectsize - this.rowdef.primaryKeyLength);
            // write new value
            try {
                this.table.set(i, this.taildef.newEntry(row.bytes(), this.rowdef.primaryKeyLength, true));
            } catch (final RowSpaceExceededException e) {
                this.table = null;
            }
            if (abandonTable()) this.table = null;
            this.file.put(i, row.bytes(), 0);
        }
        assert this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size();
        assert this.table == null || this.table.size() == this.index.size() : "table.size() = " + this.table.size() + ", index.size() = " + this.index.size();
        // return old value
        return this.rowdef.newEntry(b);
    }

    /**
     * Adds the row to the index. The row is identified by the primary key of the row.
     * @param row a index row
     * @return true if this set did _not_ already contain the given row.
     * @throws IOException
     * @throws RowSpaceExceededException
     */
    public synchronized boolean put(final Entry row) throws IOException, RowSpaceExceededException {
        assert this.file == null || this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size() + ", file = " + filename();
        assert this.table == null || this.table.size() == this.index.size() : "table.size() = " + this.table.size() + ", index.size() = " + this.index.size() + ", file = " + filename();
        assert row != null;
        assert row.bytes() != null;
        if (this.file == null || row == null || row.bytes() == null) return true;
        final int i = (int) this.index.get(row.getPrimaryKeyBytes());
        if (i == -1) {
            try {
                addUnique(row);
            } catch (final RowSpaceExceededException e) {
                if (this.table == null) throw e;
                this.table = null;
                addUnique(row);
            }
            return true;
        }

        if (this.table == null) {
            // write new value
            this.file.put(i, row.bytes(), 0);
        } else {
            // write new value
            this.file.put(i, row.bytes(), 0);
            if (abandonTable()) this.table = null; else try {
                this.table.set(i, this.taildef.newEntry(row.bytes(), this.rowdef.primaryKeyLength, true));
            } catch (final RowSpaceExceededException e) {
                this.table = null;
            }
        }
        assert this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size();
        assert this.table == null || this.table.size() == this.index.size() : "table.size() = " + this.table.size() + ", index.size() = " + this.index.size();
        return false;
    }

    public Entry put(final Entry row, final Date entryDate) throws IOException, RowSpaceExceededException {
        return replace(row);
    }

    /**
     * remove one entry from the file
     * @param i an index position within the file (not a byte position)
     * @throws IOException
     * @throws RowSpaceExceededException
     */
    private void removeInFile(final int i) throws IOException, RowSpaceExceededException {
        assert i >= 0;

        final byte[] p = new byte[this.rowdef.objectsize];
        if (this.table == null) {
            if (i == this.index.size() - 1) {
                this.file.cleanLast();
            } else {
                while (this.file.size() > 0) {
                    this.file.cleanLast(p, 0);
                    if (!(this.rowdef.objectOrder.wellformed(p, 0, this.rowdef.primaryKeyLength))) {
                        continue;
                    }
                    this.file.put(i, p, 0);
                    final byte[] k = new byte[this.rowdef.primaryKeyLength];
                    System.arraycopy(p, 0, k, 0, this.rowdef.primaryKeyLength);
                    this.index.put(k, i);
                    break;
                }
            }
        } else {
            if (i == this.index.size() - 1) {
                // special handling if the entry is the last entry in the file
                this.table.removeRow(i, false);
                this.file.cleanLast();
            } else {
                // switch values
                final Row.Entry te = this.table.removeOne();
                try {
                    this.table.set(i, te);
                } catch (final RowSpaceExceededException e) {
                    this.table = null;
                }

                while (this.file.size() > 0) {
                    this.file.cleanLast(p, 0);
                    final Row.Entry lr = this.rowdef.newEntry(p);
                    if (lr == null) {
                        // in case that p is not well-formed lr may be null
                        // drop table copy because that becomes too complicated here
                        this.table.clear();
                        this.table = null;
                        continue;
                    }
                    this.file.put(i, p, 0);
                    this.index.put(lr.getPrimaryKeyBytes(), i);
                    break;
                }
            }
        }
    }

    public boolean delete(final byte[] key) throws IOException {
        return remove(key) != null;
    }

    public synchronized Entry remove(final byte[] key) throws IOException {
        assert this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size();
        assert this.table == null || this.table.size() == this.index.size() : "table.size() = " + this.table.size() + ", index.size() = " + this.index.size();
        assert key.length == this.rowdef.primaryKeyLength;
        final int i = (int) this.index.get(key);
        if (i == -1) return null; // nothing to do

        // prepare result
        final byte[] b = new byte[this.rowdef.objectsize];
        final byte[] p = new byte[this.rowdef.objectsize];
        final int sb = this.index.size();
        int ix;
        assert i < this.index.size();
        final Row.Entry cacherow;
        if (this.table == null || (cacherow = this.table.get(i, false)) == null) {
            if (i == this.index.size() - 1) {
                // element is at last entry position
                ix = (int) this.index.remove(key);
                assert this.index.size() < i + 1 : "index.size() = " + this.index.size() + ", i = " + i;
                assert ix == i;
                this.file.cleanLast(b, 0);
            } else {
                // remove entry from index
                assert i < this.index.size() - 1 : "index.size() = " + this.index.size() + ", i = " + i;
                ix = (int) this.index.remove(key);
                assert i < this.index.size() : "index.size() = " + this.index.size() + ", i = " + i;
                assert ix == i;

                // read element that shall be removed
                this.file.get(i, b, 0);

                // fill the gap with value from last entry in file
                this.file.cleanLast(p, 0);
                this.file.put(i, p, 0);
                final byte[] k = new byte[this.rowdef.primaryKeyLength];
                System.arraycopy(p, 0, k, 0, this.rowdef.primaryKeyLength);
                try {
                    this.index.put(k, i);
                } catch (final RowSpaceExceededException e) {
                    Log.logException(e);
                    throw new IOException("RowSpaceExceededException: " + e.getMessage());
                }
            }
            assert this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size();
        } else {
            // get result value from the table copy, so we don't need to read it from the file
            System.arraycopy(key, 0, b, 0, key.length);
            System.arraycopy(cacherow.bytes(), 0, b, this.rowdef.primaryKeyLength, this.taildef.objectsize);

            if (i == this.index.size() - 1) {
                // special handling if the entry is the last entry in the file
                ix = (int) this.index.remove(key);
                assert this.index.size() < i + 1  : "index.size() = " + this.index.size() + ", i = " + i;
                assert ix == i;
                this.table.removeRow(i, false);
                this.file.cleanLast();
            } else {
                // remove entry from index
                ix = (int) this.index.remove(key);
                assert i < this.index.size() : "index.size() = " + this.index.size() + ", i = " + i;
                assert ix == i;

                // switch values:
                // remove last entry from the file copy to fill it in the gap
                final Row.Entry te = this.table.removeOne();
                // fill the gap in file copy
                try {
                    this.table.set(i, te);
                } catch (final RowSpaceExceededException e) {
                    Log.logException(e);
                    this.table = null;
                }

                // move entry from last entry in file to gap position
                this.file.cleanLast(p, 0);
                this.file.put(i, p, 0);
                // set new index for moved entry in index
                final Row.Entry lr = this.rowdef.newEntry(p);
                try {
                    this.index.put(lr.getPrimaryKeyBytes(), i);
                } catch (final RowSpaceExceededException e) {
                    this.table = null;
                    throw new IOException("RowSpaceExceededException: " + e.getMessage());
                }
            }
            assert this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size();
            assert this.table.size() == this.index.size() : "table.size() = " + this.table.size() + ", index.size() = " + this.index.size();
        }
        assert this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size();
        assert this.table == null || this.table.size() == this.index.size() : "table.size() = " + this.table.size() + ", index.size() = " + this.index.size();
        assert this.index.size() + 1 == sb : "index.size() = " + this.index.size() + ", sb = " + sb;
        return this.rowdef.newEntry(b);
    }

    public synchronized Entry removeOne() throws IOException {
        assert this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size();
        assert this.table == null || this.table.size() == this.index.size() : "table.size() = " + this.table.size() + ", index.size() = " + this.index.size();
        final byte[] le = new byte[this.rowdef.objectsize];
        final long fsb = this.file.size();
        assert fsb != 0 : "file.size() = " + fsb;
        this.file.cleanLast(le, 0);
        assert this.file.size() < fsb : "file.size() = " + this.file.size();
        final Row.Entry lr = this.rowdef.newEntry(le);
        assert lr != null;
        assert lr.getPrimaryKeyBytes() != null;
        final int is = this.index.size();
        assert this.index.has(lr.getPrimaryKeyBytes());
        final int i = (int) this.index.remove(lr.getPrimaryKeyBytes());
        assert i < 0 || this.index.size() < is : "index.size() = " + this.index.size() + ", is = " + is;
        assert i >= 0;
        if (this.table != null) {
            final int tsb = this.table.size();
            this.table.removeOne();
            assert this.table.size() < tsb : "table.size() = " + this.table.size() + ", tsb = " + tsb;
        }
        assert this.file.size() == this.index.size() : "file.size() = " + this.file.size() + ", index.size() = " + this.index.size();
        assert this.table == null || this.table.size() == this.index.size() : "table.size() = " + this.table.size() + ", index.size() = " + this.index.size();
        return lr;
    }

    public List<Row.Entry> top(int count) throws IOException {
        final ArrayList<Row.Entry> list = new ArrayList<Row.Entry>();
        if ((this.file == null) || (this.index == null)) return list;
        long i = this.file.size() - 1;
        while (count > 0 && i >= 0) {
            final byte[] b = new byte[this.rowdef.objectsize];
            this.file.get(i, b, 0);
            list.add(this.rowdef.newEntry(b));
            i--;
            count--;
        }
        return list;
    }

    public synchronized void clear() throws IOException {
        final File f = this.file.filename();
        this.file.close();
        this.file = null;
        FileUtils.deletedelete(f);

        // make new file
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
        } catch (final FileNotFoundException e) {
            // should not happen
            Log.logSevere("Table", "", e);
        }
        if (fos != null) try { fos.close(); } catch (final IOException e) {}

        this.file = new BufferedRecords(new Records(f, this.rowdef.objectsize), this.buffersize);

        // initialize index and copy table
        this.table = (this.table == null) ? null : new RowSet(this.taildef);
        this.index.clear();
    }

    public Row row() {
        return this.rowdef;
    }

    public int size() {
        return this.index.size();
    }

    public boolean isEmpty() {
        return this.index.isEmpty();
    }

    public Iterator<Entry> iterator() {
        try {
            return rows();
        } catch (final IOException e) {
            return null;
        }
    }

    public synchronized CloneableIterator<Entry> rows() throws IOException {
        this.file.flushBuffer();
        return new rowIteratorNoOrder();
    }

    private class rowIteratorNoOrder implements CloneableIterator<Entry> {
        Iterator<Row.Entry> i;
        int idx;
        byte[] key;

        public rowIteratorNoOrder() {
            // don't use the ChunkIterator here because it may create too many open files during string load
            //ri = new ChunkIterator(tablefile, rowdef.objectsize, rowdef.objectsize);
            this.i = Table.this.index.iterator();
        }

        public CloneableIterator<Entry> clone(final Object modifier) {
            return new rowIteratorNoOrder();
        }

        public boolean hasNext() {
            return this.i != null && this.i.hasNext();
        }

        public Entry next() {
            final Row.Entry entry = this.i.next();
            if (entry == null) return null;
            this.key = entry.getPrimaryKeyBytes();
            if (this.key == null) return null;
            this.idx = (int) entry.getColLong(1);
            try {
                return get(this.key, false);
            } catch (final IOException e) {
                return null;
            }
        }

        public void remove() {
            if (this.key != null) {
                try {
                    removeInFile(this.idx);
                } catch (final IOException e) {
                } catch (final RowSpaceExceededException e) {
                }
                this.i.remove();
            }
        }

    }

    public synchronized CloneableIterator<Entry> rows(final boolean up, final byte[] firstKey) throws IOException {
        return new rowIterator(up, firstKey);
    }

    private class rowIterator implements CloneableIterator<Entry> {
        private final Iterator<byte[]> i;
        private final boolean up;
        private final byte[] fk;
        private int c;

        private rowIterator(final boolean up, final byte[] firstKey) {
            this.up = up;
            this.fk = firstKey;
            this.i  = Table.this.index.keys(up, firstKey);
            this.c = -1;
        }

        public CloneableIterator<Entry> clone(final Object modifier) {
            return new rowIterator(this.up, this.fk);
        }

        public boolean hasNext() {
            return this.i.hasNext();
        }

        public Entry next() {
            final byte[] k = this.i.next();
            assert k != null;
            if (k == null) return null;
            this.c = (int) Table.this.index.get(k);
            if (this.c < 0) throw new ConcurrentModificationException(); // this should only happen if the table was modified during the iteration
            final byte[] b = new byte[Table.this.rowdef.objectsize];
            final Row.Entry cacherow;
            if (Table.this.table == null || (cacherow = Table.this.table.get(this.c, false)) == null) {
                // read from file
                try {
                    Table.this.file.get(this.c, b, 0);
                } catch (final IOException e) {
                    Log.logSevere("Table", "", e);
                    return null;
                }
            } else {
                // compose from table and key
                assert cacherow != null;
                if (cacherow == null) return null;
                System.arraycopy(k, 0, b, 0, Table.this.rowdef.primaryKeyLength);
                System.arraycopy(cacherow.bytes(), 0, b, Table.this.rowdef.primaryKeyLength, Table.this.taildef.objectsize);
            }
            return Table.this.rowdef.newEntry(b);
        }

        public void remove() {
            throw new UnsupportedOperationException("no remove in Table.rowIterator");
        }

    }

    private static byte[] testWord(final char c) {
        return new byte[]{(byte) c, 32, 32, 32};
    }

    private static String[] permutations(final int letters) {
        String p = "";
        for (int i = 0; i < letters; i++) p = p + ((char) (('A') + i));
        return permutations(p);
    }

    private static String[] permutations(final String source) {
        if (source.length() == 0) return new String[0];
        if (source.length() == 1) return new String[]{source};
        final char c = source.charAt(0);
        final String[] recres = permutations(source.substring(1));
        final String[] result = new String[source.length() * recres.length];
        for (int perm = 0; perm < recres.length; perm++) {
            result[perm * source.length()] = c + recres[perm];
            for (int pos = 1; pos < source.length() - 1; pos++) {
                result[perm * source.length() + pos] = recres[perm].substring(0, pos) + c + recres[perm].substring(pos);
            }
	    result[perm * source.length() + source.length() - 1] = recres[perm] + c;
        }
        return result;
    }

    private static Table testTable(final File f, final String testentities, final boolean useTailCache, final boolean exceed134217727) throws IOException, RowSpaceExceededException {
        if (f.exists()) FileUtils.deletedelete(f);
        final Row rowdef = new Row("byte[] a-4, byte[] b-4", NaturalOrder.naturalOrder);
        final Table tt = new Table(f, rowdef, 100, 0, useTailCache, exceed134217727);
        byte[] b;
        final Row.Entry row = rowdef.newEntry();
        for (int i = 0; i < testentities.length(); i++) {
            b = testWord(testentities.charAt(i));
            row.setCol(0, b);
            row.setCol(1, b);
            tt.put(row);
        }
        return tt;
    }

    private static int countElements(final Table t) {
        int count = 0;
        for (final Row.Entry row: t) {
            count++;
            if (row == null) System.out.println("ERROR! null element found");
            // else System.out.println("counted element: " + new
            // String(n.getKey()));
        }
        return count;
    }

    public static void bigtest(final int elements, final File testFile, final boolean useTailCache, final boolean exceed134217727) {
        System.out.println("starting big test with " + elements + " elements:");
        final long start = System.currentTimeMillis();
        final String[] s = permutations(elements);
        Table tt;
        int count;
        try {
            for (int i = 0; i < s.length; i++) {
                System.out.println("*** probing tree " + i + " for permutation " + s[i]);
                // generate tree and delete elements
                tt = testTable(testFile, s[i], useTailCache, exceed134217727);
                count = countElements(tt);
                if (count != tt.size()) {
                    System.out.println("wrong size for " + s[i] + ": count = " + count + ", size() = " + tt.size());
                }
                tt.close();
                for (final String element : s) {
                    tt = testTable(testFile, s[i], useTailCache, exceed134217727);
                    // delete by permutation j
                    for (int elt = 0; elt < element.length(); elt++) {
                        tt.remove(testWord(element.charAt(elt)));
                        count = countElements(tt);
                        if (count != tt.size()) {
                            System.out.println("ERROR! wrong size for probe tree " + s[i] + "; probe delete " + element + "; position " + elt + "; count = " + count + ", size() = " + tt.size());
                        }
                    }
                    tt.close();
                }
            }
            System.out.println("FINISHED test after " + ((System.currentTimeMillis() - start) / 1000) + " seconds.");
        } catch (final Exception e) {
            Log.logException(e);
            System.out.println("TERMINATED");
        }
    }

    public void print() throws IOException {
        System.out.println("PRINTOUT of table, length=" + size());
        Entry row;
        byte[] key;
        final CloneableIterator<byte[]> i = keys(true, null);
        while (i.hasNext()) {
            System.out.print("row " + i + ": ");
            key = i.next();
            row = get(key, false);
            System.out.println(row.toString());
        }
        System.out.println("EndOfTable");
    }

    public static void main(final String[] args) {
        // open a file, add one entry and exit
        final File f = new File(args[0]);
        System.out.println("========= Testcase: no tail cache:");
        bigtest(5, f, false, false);
        System.out.println("========= Testcase: with tail cache:");
        bigtest(5, f, true, true);
        /*
        kelondroRow row = new kelondroRow("byte[] key-4, byte[] x-5", kelondroNaturalOrder.naturalOrder, 0);
        try {
            kelondroTABLE t = new kelondroTABLE(f, row);
            kelondroRow.Entry entry = row.newEntry();
            entry.setCol(0, "abcd".getBytes());
            entry.setCol(1, "dummy".getBytes());
            t.put(entry);
            t.close();
        } catch (IOException e) {
            Log.logException(e);
        }
        */
    }

    public void deleteOnExit() {
        this.file.deleteOnExit();
    }

}
