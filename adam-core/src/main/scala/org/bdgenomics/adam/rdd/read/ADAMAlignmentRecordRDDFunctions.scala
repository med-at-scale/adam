/**
 * Licensed to Big Data Genomics (BDG) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The BDG licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bdgenomics.adam.rdd.read

import fi.tkk.ics.hadoop.bam.SAMRecordWritable
import net.sf.samtools.SAMFileHeader
import org.apache.hadoop.io.LongWritable
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.bdgenomics.adam.algorithms.consensus.{
  ConsensusGenerator,
  ConsensusGeneratorFromReads
}
import org.bdgenomics.adam.converters.AlignmentRecordConverter
import org.bdgenomics.adam.models._
import org.bdgenomics.adam.rdd.ADAMContext._
import org.bdgenomics.adam.rdd.ADAMSequenceDictionaryRDDAggregator
import org.bdgenomics.adam.rdd.read.ADAMAlignmentRecordContext._
import org.bdgenomics.adam.rdd.read.correction.{ ErrorCorrection, TrimReads }
import org.bdgenomics.adam.rdd.read.realignment.RealignIndels
import org.bdgenomics.adam.rdd.read.recalibration.BaseQualityRecalibration
import org.bdgenomics.adam.rich.RichAlignmentRecord
import org.bdgenomics.adam.util.MapTools
import org.bdgenomics.formats.avro._

class ADAMAlignmentRecordRDDFunctions(rdd: RDD[AlignmentRecord]) extends ADAMSequenceDictionaryRDDAggregator[AlignmentRecord](rdd) {

  /**
   * Calculates the subset of the RDD whose AlignmentRecords overlap the corresponding
   * query ReferenceRegion.  Equality of the reference sequence (to which these are aligned)
   * is tested by string equality of the names.  AlignmentRecords whose 'getReadMapped' method
   * return 'false' are ignored.
   *
   * The end of the record against the reference sequence is calculated from the cigar string
   * using the ADAMContext.referenceLengthFromCigar method.
   *
   * @param query The query region, only records which overlap this region are returned.
   * @return The subset of AlignmentRecords (corresponding to either primary or secondary alignments) that
   *         overlap the query region.
   */
  def filterByOverlappingRegion(query: ReferenceRegion): RDD[AlignmentRecord] = {
    def overlapsQuery(rec: AlignmentRecord): Boolean =
      rec.getReadMapped &&
        rec.getContig.getContigName.toString == query.referenceName &&
        rec.getStart < query.end &&
        rec.getEnd > query.start
    rdd.filter(overlapsQuery)
  }

  /**
   * Saves an RDD of ADAM read data into the SAM/BAM format.
   *
   * @param filePath Path to save files to.
   * @param asSam Selects whether to save as SAM or BAM. The default value is true (save in SAM format).
   */
  def adamSAMSave(filePath: String, asSam: Boolean = true) {

    // convert the records
    val (convertRecords: RDD[SAMRecordWritable], header: SAMFileHeader) = rdd.adamConvertToSAM()

    // add keys to our records
    val withKey = convertRecords.keyBy(v => new LongWritable(v.get.getAlignmentStart))

    // attach header to output format
    asSam match {
      case true  => ADAMSAMOutputFormat.addHeader(header)
      case false => ADAMBAMOutputFormat.addHeader(header)
    }

    // write file to disk
    val conf = rdd.context.hadoopConfiguration
    asSam match {
      case true  => withKey.saveAsNewAPIHadoopFile(filePath, classOf[LongWritable], classOf[SAMRecordWritable], classOf[ADAMSAMOutputFormat[LongWritable]], conf)
      case false => withKey.saveAsNewAPIHadoopFile(filePath, classOf[LongWritable], classOf[SAMRecordWritable], classOf[ADAMBAMOutputFormat[LongWritable]], conf)
    }
  }

  def getSequenceRecordsFromElement(elem: AlignmentRecord): scala.collection.Set[SequenceRecord] = {
    SequenceRecord.fromADAMRecord(elem)
  }

  /**
   * Collects a dictionary summarizing the read groups in an RDD of ADAMRecords.
   *
   * @return A dictionary describing the read groups in this RDD.
   */
  def adamGetReadGroupDictionary(): RecordGroupDictionary = {
    val rgNames = rdd.flatMap(RecordGroup(_))
      .distinct()
      .collect()
      .toSeq

    new RecordGroupDictionary(rgNames)
  }

  /**
   * Converts an RDD of ADAM read records into SAM records.
   *
   * @return Returns a SAM/BAM formatted RDD of reads, as well as the file header.
   */
  def adamConvertToSAM(): (RDD[SAMRecordWritable], SAMFileHeader) = {
    // collect global summary data
    val sd = rdd.adamGetSequenceDictionary()
    val rgd = rdd.adamGetReadGroupDictionary()

    // create conversion object
    val adamRecordConverter = new AlignmentRecordConverter

    // create header
    val header = adamRecordConverter.createSAMHeader(sd, rgd)

    // broadcast for efficiency
    val hdrBcast = rdd.context.broadcast(SAMFileHeaderWritable(header))

    // map across RDD to perform conversion
    val convertedRDD: RDD[SAMRecordWritable] = rdd.map(r => {
      // must wrap record for serializability
      val srw = new SAMRecordWritable()
      srw.set(adamRecordConverter.convert(r, hdrBcast.value))
      srw
    })

    (convertedRDD, header)
  }

  /**
   * Cuts reads into _k_-mers, and then counts the number of occurrences of each _k_-mer.
   *
   * @param kmerLength The value of _k_ to use for cutting _k_-mers.
   * @return Returns an RDD containing k-mer/count pairs.
   *
   * @see adamCountQmers
   */
  def adamCountKmers(kmerLength: Int): RDD[(String, Long)] = {
    rdd.flatMap(r => {
      // cut each read into k-mers, and attach a count of 1L
      r.getSequence
        .toString
        .sliding(kmerLength)
        .map(k => (k, 1L))
    }).reduceByKey((k1: Long, k2: Long) => k1 + k2)
  }

  /**
   * Cuts reads into _q_-mers, and then finds the _q_-mer weight. Q-mers are described in:
   *
   * Kelley, David R., Michael C. Schatz, and Steven L. Salzberg. "Quake: quality-aware detection
   * and correction of sequencing errors." Genome Biol 11.11 (2010): R116.
   *
   * _Q_-mers are _k_-mers weighted by the quality score of the bases in the _k_-mer.
   *
   * @param qmerLength The value of _q_ to use for cutting _q_-mers.
   * @return Returns an RDD containing q-mer/weight pairs.
   *
   * @see adamCountKmers
   */
  def adamCountQmers(qmerLength: Int): RDD[(String, Double)] = {
    ErrorCorrection.countQmers(rdd, qmerLength)
  }

  def adamSortReadsByReferencePosition(): RDD[AlignmentRecord] = {
    log.info("Sorting reads by reference position")

    // NOTE: In order to keep unmapped reads from swamping a single partition
    // we place them in a range of referenceIds at the end of the file.
    // The referenceId is an Int and typical only a few dozen values are even used.
    // These referenceId values are not stored; they are only used during sorting.
    val unmappedReferenceNames = new Iterator[String] with Serializable {
      var currentOffsetFromEnd = 0

      def hasNext: Boolean = true

      def next(): String = {
        currentOffsetFromEnd += 1
        if (currentOffsetFromEnd > 10000) {
          currentOffsetFromEnd = 0
        }
        // NB : this is really ugly - any better way to manufacture
        // string values that are greater than anything else we care
        // about?
        "unmapped" + (Int.MaxValue - currentOffsetFromEnd).toString
      }
    }

    rdd.map(p => {
      val referencePos = ReferencePosition(p) match {
        case None =>
          // Move unmapped reads to the end of the file
          ReferencePosition(
            unmappedReferenceNames.next(), Long.MaxValue)
        case Some(pos) => pos
      }
      (referencePos, p)
    }).sortByKey().map(p => p._2)
  }

  def adamMarkDuplicates(): RDD[AlignmentRecord] = {
    MarkDuplicates(rdd)
  }

  def adamBQSR(knownSnps: Broadcast[SnpTable]): RDD[AlignmentRecord] = {
    BaseQualityRecalibration(rdd, knownSnps)
  }

  /**
   * Realigns indels using a concensus-based heuristic.
   *
   * @see RealignIndels
   *
   * @param isSorted If the input data is sorted, setting this parameter to true avoids a second sort.
   * @param maxIndelSize The size of the largest indel to use for realignment.
   * @param maxConsensusNumber The maximum number of consensus sequences to realign against per
   * target region.
   * @param lodThreshold Log-odds threhold to use when realigning; realignments are only finalized
   * if the log-odds threshold is exceeded.
   * @param maxTargetSize The maximum width of a single target region for realignment.
   *
   * @return Returns an RDD of mapped reads which have been realigned.
   */
  def adamRealignIndels(consensusModel: ConsensusGenerator = new ConsensusGeneratorFromReads,
                        isSorted: Boolean = false,
                        maxIndelSize: Int = 500,
                        maxConsensusNumber: Int = 30,
                        lodThreshold: Double = 5.0,
                        maxTargetSize: Int = 3000): RDD[AlignmentRecord] = {
    RealignIndels(rdd, consensusModel, isSorted, maxIndelSize, maxConsensusNumber, lodThreshold)
  }

  // Returns a tuple of (failedQualityMetrics, passedQualityMetrics)
  def adamFlagStat(): (FlagStatMetrics, FlagStatMetrics) = {
    FlagStat(rdd)
  }

  /**
   * Groups all reads by record group and read name
   * @return SingleReadBuckets with primary, secondary and unmapped reads
   */
  def adamSingleReadBuckets(): RDD[SingleReadBucket] = {
    SingleReadBucket(rdd)
  }

  /**
   * Groups all reads by reference position and returns a non-aggregated pileup RDD.
   *
   * @param secondaryAlignments Creates pileups for non-primary aligned reads. Default is false.
   * @return Pileup without aggregation
   */
  def adamRecords2Pileup(secondaryAlignments: Boolean = false): RDD[Pileup] = {
    val helper = new Reads2PileupProcessor(secondaryAlignments)
    helper.process(rdd)
  }

  /**
   * Groups all reads by reference position, with all reference position bases grouped
   * into a rod.
   *
   * @param bucketSize Size in basepairs of buckets. Larger buckets take more time per
   * bucket to convert, but have lower skew. Default is 1000.
   * @param secondaryAlignments Creates rods for non-primary aligned reads. Default is false.
   * @return RDD of Rods.
   */
  def adamRecords2Rods(bucketSize: Int = 1000,
                       secondaryAlignments: Boolean = false): RDD[Rod] = {

    /**
     * Maps a read to one or two buckets. A read maps to a single bucket if both
     * it's start and end are in a single bucket.
     *
     * @param r Read to map.
     * @return List containing one or two mapping key/value pairs.
     */
    def mapToBucket(r: AlignmentRecord): List[(ReferencePosition, AlignmentRecord)] = {
      val s = r.getStart / bucketSize
      val e = r.getEnd / bucketSize
      val name = r.getContig.getContigName

      if (s == e) {
        List((new ReferencePosition(name, s), r))
      } else {
        List((new ReferencePosition(name, s), r), (new ReferencePosition(name, e), r))
      }
    }

    val bucketedReads = rdd.filter(_.getStart != null)
      .flatMap(mapToBucket)
      .groupByKey()

    val pp = new Reads2PileupProcessor(secondaryAlignments)

    /**
     * Converts all reads in a bucket into rods.
     *
     * @param bucket Tuple of (bucket number, reads in bucket).
     * @return A sequence containing the rods in this bucket.
     */
    def bucketedReadsToRods(bucket: (ReferencePosition, Iterable[AlignmentRecord])): Iterable[Rod] = {
      val (_, bucketReads) = bucket

      bucketReads.flatMap(pp.readToPileups)
        .groupBy(ReferencePosition(_))
        .toList
        .map(g => Rod(g._1, g._2.toList)).toSeq
    }

    bucketedReads.flatMap(bucketedReadsToRods)
  }

  /**
   * Converts a set of records into an RDD containing the pairs of all unique tagStrings
   * within the records, along with the count (number of records) which have that particular
   * attribute.
   *
   * @return An RDD of attribute name / count pairs.
   */
  def adamCharacterizeTags(): RDD[(String, Long)] = {
    rdd.flatMap(RichAlignmentRecord(_).tags.map(attr => (attr.tag, 1L))).reduceByKey(_ + _)
  }

  /**
   * Calculates the set of unique attribute <i>values</i> that occur for the given
   * tag, and the number of time each value occurs.
   *
   * @param tag The name of the optional field whose values are to be counted.
   * @return A Map whose keys are the values of the tag, and whose values are the number of time each tag-value occurs.
   */
  def adamCharacterizeTagValues(tag: String): Map[Any, Long] = {
    adamFilterRecordsWithTag(tag).flatMap(RichAlignmentRecord(_).tags.find(_.tag == tag)).map(
      attr => Map(attr.value -> 1L)).reduce {
        (map1: Map[Any, Long], map2: Map[Any, Long]) =>
          MapTools.add(map1, map2)
      }
  }

  /**
   * Returns the subset of the ADAMRecords which have an attribute with the given name.
   * @param tagName The name of the attribute to filter on (should be length 2)
   * @return An RDD[Read] containing the subset of records with a tag that matches the given name.
   */
  def adamFilterRecordsWithTag(tagName: String): RDD[AlignmentRecord] = {
    assert(tagName.length == 2,
      "withAttribute takes a tagName argument of length 2; tagName=\"%s\"".format(tagName))
    rdd.filter(RichAlignmentRecord(_).tags.exists(_.tag == tagName))
  }

  /**
   * Trims bases from the start and end of all reads in an RDD.
   *
   * @param trimStart Number of bases to trim from the start of the read.
   * @param trimEnd Number of bases to trim from the end of the read.
   * @param readGroup Optional parameter specifying which read group to trim. If omitted,
   * all reads are trimmed.
   * @return Returns an RDD of trimmed reads.
   *
   * @note Trimming parameters must be >= 0.
   */
  def adamTrimReads(trimStart: Int, trimEnd: Int, readGroup: String = null): RDD[AlignmentRecord] = {
    TrimReads(rdd, trimStart, trimEnd, readGroup)
  }

  /**
   * Trims low quality read prefix/suffixes. The average read prefix/suffix quality is
   * calculated from the Phred scaled qualities for read bases. We trim suffixes/prefixes
   * that are below a user provided threshold.
   *
   * @param phredThreshold Phred score for trimming. Defaut value is 20.
   * @return Returns an RDD of trimmed reads.
   */
  def adamTrimLowQualityReadGroups(phredThreshold: Int = 20): RDD[AlignmentRecord] = {
    TrimReads(rdd, phredThreshold)
  }
}
