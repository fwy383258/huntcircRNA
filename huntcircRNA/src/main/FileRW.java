package main;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;

import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.util.IntervalTree;
import htsjdk.samtools.util.IntervalTree.Node;

public class FileRW {
	
	public static Integer halfDev = 5;
	public static Integer sup_read = 2;
	public static String[] AG = {"AG","AC","AG"};
	public static String[] GT = {"GT","AT","GC"};
	public static String[] AG_neg = {"AC","AT","GC"};
	public static String[] GT_neg = {"CT","GT","CT"};
	private static int ip_reads = 0; // no rrna reads in IP bam
	private static int input_reads = 0; // no rrna reads in input bam
	private static int[] ip_chr_reads = new int[25];
	private static int[] input_chr_reads = new int[25];
	
	/**
	 * read bam file for back splicing junctions and put all reads filted into trees
	 * @param args the command line parameters;
	 * @param itree the tree of ip file (output);
	 * @param itree_input the tree of input file (output);
	 * @return a chr divided back splicing junctions
	 */
	public static ArrayList<HashMap<String,JuncInfo>> filtBam(InParam args, ArrayList<IntervalTree<ReadInfo>> itree, ArrayList<IntervalTree<ReadInfo>> itree_input){
		ArrayList<HashMap<String,JuncInfo>> out = creatJuncTable();
		String ip_file = args.getIp_file();
		String input_file = args.getInput_file();
		SamReader reader = null;
		RemoverRNA r = new RemoverRNA(args.getRrna_bed()); // initial rRNA removing function
		setToZero(ip_chr_reads); //initial reads count in ip chrs
		setToZero(input_chr_reads); // initial reads count in input chrs
		try {
			if (ip_file !=null) {
				File fi = new File(ip_file);
				FileRW.printNow("Scaning " + ip_file + " for BSJ at");
				reader = SamReaderFactory.makeDefault().open(fi);
				ip_reads = filtBam(reader, out, itree, true, args, r);
				reader.close();
				int juncs = 0;
				for (int i = 0; i < out.size(); ++i) {
					juncs += out.get(i).size();
				}
				System.out.println("Possible BSJ in IP bam: " + juncs);
				System.out.println("IP_Reads: " + ip_reads);
			}
			
			if (input_file != null) {
				File fi_2 = new File(input_file);
				FileRW.printNow("Scaning " + input_file + " for BSJ at");
				reader = SamReaderFactory.makeDefault().open(fi_2);
				input_reads = filtBam(reader, out, itree_input, false, args, r);
				reader.close();
				System.out.println("INPUT_Reads: " + input_reads);
			}
		}
		catch(IOException e){
			e.printStackTrace();
		}
		finally{
			if (reader != null){
				try{
					reader.close();
				}
				catch(IOException e1){
				}
			}
		}
		return out;
	}
	
	/**
	 * use paired clipping signal (as GT-AG) in genome to filt junctions and fix their postion to the clipping position 
	 * @param fileName the genome file path;
	 * @param in junction to get filted and then return the filted result (output);
	 * @param lengths chromosome lengths in the genome;
	 */
	public static void filtGTAG(String fileName, ArrayList<HashMap<String,JuncInfo>> in, ArrayList<Integer> lengths){
		if (fileName == null) {
			System.out.println("No Genome File For GT-AG Signal");
			return;
		}
		File fi = new File(fileName);
		BufferedReader reader = null;
		FileRW.printNow("Start filt GTAG at");
		try {
			reader = new BufferedReader(new FileReader(fi));
			String tempString = null;
			int chrNum = 0;
			String chr = null;
			while ((tempString = reader.readLine()) != null){
				if (tempString.charAt(0) == '>'){
					chr = tempString.substring(1);
					System.out.println("Searching " + chr);
					chrNum = ExonInfo.chrSymbolToNum(chr);
				}
				else {
					System.out.println("Possible BSJ:" + in.get(chrNum).size());
					HashMap<String, JuncInfo> fix_junc = fixChrJunc(tempString, in.get(chrNum));
					in.set(chrNum, fix_junc);
					if (lengths.size() >= 25) {
						lengths.set(chrNum, tempString.length());
					}
					FileRW.printNow(chr + " filted BSJ: " + in.get(chrNum).size() + " at");
				}
			}
			reader.close();
		}
		catch(IOException e){
			e.printStackTrace();
		}
		finally{
			if (reader != null){
				try{
					reader.close();
				}
				catch(IOException e1){
				}
			}
		}
	}
	
	/**
	 * load genes and exons in gtf file and then use them to filt junctions
	 * @param args command line parameters;
	 * @param juncs junctions to get filted and then return the filted result (output);
	 * @param genes stored gene tree when finish loading;
	 */
	public static void loadGenes(InParam args, ArrayList<HashMap<String,JuncInfo>> juncs, ArrayList<IntervalTree<ArrayList<Gene>>> genes){
		for (int i = 0; i < 25; ++i) {
			IntervalTree<ArrayList<Gene>> tree = new IntervalTree<>();
			tree.setSentinel(null);
			genes.add(tree);
		}
		String temp_line = null;
		Gene gene = null;
		Transcript the_script = null;
		ExonInfo exon = null;
		
		if (args.getGtf_file() == null) {
			return;
		}
		File f_gtf = new File(args.getGtf_file());
		BufferedReader exon_gtf = null;
		try {
			exon_gtf = new BufferedReader(new FileReader(f_gtf));
			int exon_end = 0;
			while((temp_line = exon_gtf.readLine()) != null) {
				String[] cols = temp_line.split("\t");
				if (cols.length < 5) {
					continue;
				}
				if (cols[2].equals("exon")) {
					int start = Integer.parseInt(cols[3]);
					int end = Integer.parseInt(cols[4]);
					char strand = cols[6].charAt(0);
					int chr_array = ExonInfo.chrSymbolToNum(cols[0]);
					if (chr_array >= 0 && chr_array < 25) {
						exon = new ExonInfo();
						exon.setChr_symbol(cols[0]);
						exon.setRead_count(0);
						exon.setStart_position(start);
						exon.setEnd_position(end);
						int index = temp_line.indexOf("transcript_id") + 15;
						if (index < 15) {
							exon_gtf.close();
							return;
						}
						String transcript_id = temp_line.substring(index, temp_line.indexOf('"', index));
						if (the_script != null && transcript_id.equals(the_script.getId())) {
							the_script.addExon(exon);
						}
						else {
							if (the_script != null) {
								the_script.setEnd(exon_end);
								the_script.sortExons(true);
							}
							index = temp_line.indexOf("gene_id") + 9;
							if (index < 9) {
								exon_gtf.close();
								return;
							}
							String gene_id = temp_line.substring(index, temp_line.indexOf('"', index));
							if (gene == null || !gene_id.equals(gene.getGene_id())) {
								if (gene != null) {
									gene.setEnd(exon_end);
									IntervalTree<ArrayList<Gene>> gene_tree = genes.get(chr_array);
									ArrayList<Gene> old = gene_tree.put(gene.getStart(), gene.getEnd(), null);
									ArrayList<Gene> value = old;
									if (value == null) {
										value = new ArrayList<>();
									}
									value.add(gene);
									gene_tree.put(start, end, value);
								}
								gene = new Gene();
								gene.setStart(start);
								gene.setGene_id(gene_id);
								gene.setGene_symbol(gene_id);
								if ((index = temp_line.indexOf("gene_name") + 11) != 10) {
									gene.setGene_symbol(temp_line.substring(index, temp_line.indexOf('"', index)));
								}
								gene.setStrand(strand);
							}
							the_script = new Transcript(gene, transcript_id, new ArrayList<ExonInfo>(), start, 0, strand, 0, false);
							gene.getTranscripts().add(the_script);
							exon_end = 0;
						}
						if (exon_end < end) {
							exon_end = end;
						}
					}
				}
				else if(cols[2].equals("transcript")) {
					if (the_script != null) {
						the_script.sortExons(true);
					}
					int start = Integer.parseInt(cols[3]);
					int end = Integer.parseInt(cols[4]);
					int chr_array = ExonInfo.chrSymbolToNum(cols[0]);
					char strand = cols[6].charAt(0);
					String[] infos = cols[8].split("\"");
					if (infos[2].contains("transcript_id")) {
						the_script = new Transcript(gene, infos[3], new ArrayList<ExonInfo>(), start, end, strand, 0, false);
						if (chr_array >= 0 && chr_array < 25) {
							gene.getTranscripts().add(the_script);
						}
					}
				}
				else if (cols[2].equals("gene")) {
					int start = Integer.parseInt(cols[3]);
					int end = Integer.parseInt(cols[4]);
					gene = new Gene();
					int index = temp_line.indexOf("gene_id") + 9;
					if (index < 9) {
						exon_gtf.close();
						return;
					}
					String gene_id = temp_line.substring(index, temp_line.indexOf('"', index));
					gene.setGene_id(gene_id);
					gene.setGene_symbol(temp_line.substring(temp_line.indexOf("gene_name") + 11, temp_line.indexOf("; level") - 1));
					gene.setStart(start);
					gene.setEnd(end);
					gene.setStrand(cols[6].charAt(0));
					int chr_array = ExonInfo.chrSymbolToNum(cols[0]);
					if (chr_array >= 0 && chr_array < 25) {
						IntervalTree<ArrayList<Gene>> gene_tree = genes.get(chr_array);
						ArrayList<Gene> old = gene_tree.put(start, end, null);
						ArrayList<Gene> value = old;
						if (value == null) {
							value = new ArrayList<>();
						}
						value.add(gene);
						gene_tree.put(start, end, value);
					}
				}
			}
			exon_gtf.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		filtJuncTable(juncs, genes);
		return;
	}
	
	/**
	 * read junction bed file to get junctions
	 * @param bed_file bed file path;
	 * @return a list of junctions that stored in chromosomes;
	 */
	public static ArrayList<HashMap<String,JuncInfo>> readJuncs(String bed_file){
		ArrayList<HashMap<String,JuncInfo>> out = new ArrayList<>();
		out = creatJuncTable();
		File fi = new File(bed_file);
		BufferedReader reader = null;
		try {
			reader = new BufferedReader(new FileReader(fi));
			String line = null;
			while ((line = reader.readLine()) != null) {
				String[] cols = line.split("\t");
				int chr_num = ExonInfo.chrSymbolToNum(cols[0]);
				if (chr_num >= 0 && chr_num < 25) {
					HashMap<String, JuncInfo> juncs = out.get(chr_num);
					int start = Integer.parseInt(cols[1]);
					int end = Integer.parseInt(cols[2]);
					String key = cols[1] + "\t" + cols[2];
					if (!juncs.containsKey(key)) {
						JuncInfo junc = new JuncInfo(start, end);
						juncs.put(key, junc);
						if (cols[5].length() == 1) {
							junc.setStrand(cols[5].charAt(0));
						}
					}
				}
			}
		}catch(IOException e) {
			e.printStackTrace();
		}finally {
			if (reader != null){
				try{
					reader.close();
				}
				catch(IOException e1){
				}
			}
		}
		return out;
	}
	
	/**
	 * overwrite a string list into a file
	 * @param fileName the file name/path to output a list of string;
	 * @param out the content to be output;
	 */
	public static void fileWrite(String fileName, ArrayList<String> out){
		File fo = new File (fileName);
		BufferedWriter writer = null;
		try {
			writer = new BufferedWriter(new FileWriter(fo));
			for (String tempString : out){
				writer.write(tempString);
				writer.newLine();
			}
			writer.flush();
			writer.close();
		}
		catch(IOException e) {
			e.printStackTrace();
		}
		finally {
			try {
				writer.close();
			}
			catch(IOException e1){
			}
		}
	}

	/**
	 * append a string list into a file
	 * @param fileName the file name to output;
	 * @param out the content to be put out;
	 */
	public static void fileAppend(String fileName, ArrayList<String> out){
		File fo = new File (fileName);
		BufferedWriter writer = null;
		try {
			writer = new BufferedWriter(new FileWriter(fo, true));
			for (String tempString : out){
				writer.write(tempString);
				writer.newLine();
			}
			writer.flush();
			writer.close();
		}
		catch(IOException e) {
			e.printStackTrace();
		}
		finally {
			try {
				writer.close();
			}
			catch(IOException e1){
			}
		}
	}
	
	/**
	 * fix the position of backjunctions and filt them by GT-AG signal in the ref chromosome
	 * @param chr the content of a ref chromosome(base sequence);
	 * @param potent_juncs the potential backjunctions of this chromosome;
	 * @return filted backjunctions;
	 */
	public static HashMap<String, JuncInfo> fixChrJunc(String chr, HashMap<String, JuncInfo> potent_juncs){
		HashMap<String, JuncInfo> out= new HashMap<String, JuncInfo>();
		HandleReads handle = new HandleReads();
		for (Entry<String, JuncInfo> entry : potent_juncs.entrySet()){
			JuncInfo thisJunc = entry.getValue();
			int ag = thisJunc.getSP();
			int gt = thisJunc.getEP();
			int devOff = 0;
			int end_dev_Off = 0;
			String AGDev = null;
			String GTDev = null;
			if (!thisJunc.isFix_start_exon()) {
				if (ag > halfDev){
					devOff = halfDev + 1;
				}
				else{
					devOff = ag;
				}
				AGDev = chr.substring(ag - devOff, ag + halfDev);
			}
			if (!thisJunc.isFix_end_exon()) {
				end_dev_Off = halfDev;
				if (gt + halfDev >= chr.length()){
					end_dev_Off = chr.length() - gt - 1;
				}
				GTDev = chr.substring(gt - halfDev - 1, gt + end_dev_Off);
			}
			int[] fix = null;
			if (thisJunc.getStrand() == '+') {
				fix = handle.fixPosOff(AGDev, GTDev, devOff, halfDev + 1, AG, GT);
			}
			else if (thisJunc.getStrand() == '-') {
				fix = handle.fixPosOff(AGDev, GTDev, devOff, halfDev + 1, AG_neg, GT_neg);
			}
			else {
				fix = handle.fixPosOff(AGDev, GTDev, devOff, halfDev + 1, AG, GT);
				if (fix[0] == Integer.MIN_VALUE && fix[1] == Integer.MIN_VALUE) {
					fix = handle.fixPosOff(AGDev, GTDev, devOff, halfDev + 1, AG_neg, GT_neg);
				}
			}
			if (fix[0] != Integer.MIN_VALUE && fix[1] != Integer.MIN_VALUE) {
				ag = ag + fix[0];
				gt = gt + fix[1];
				String key = ag + "\t" + gt;
				if (out.containsKey(key)) {
					out.get(key).addReadID(thisJunc.getReadID());
					out.get(key).addInputID(thisJunc.getInputids());
				}
				else {
					thisJunc.setSP(ag);
					thisJunc.setEP(gt);
					out.put(key, thisJunc);
				}
			}
		}
		return out;
	}
	
	/**
	 * counting single end support reads to junctions 
	 * @param juncs the junctions divided into chromosomes;
	 * @param itree the tree of reads attached to the ip file;
	 * @param itree_input the tree of reads attached to the input file;
	 */
	public static void countReadsExon(ArrayList<HashMap<String,JuncInfo>> juncs, ArrayList<IntervalTree<ReadInfo>> itree, ArrayList<IntervalTree<ReadInfo>> itree_input) {
		
		countSamfile(itree, juncs, true);
		countSamfile(itree_input, juncs, false);
		
	}
	
	/**
	 * arrange the junctions into a list of string for detailed output
	 * @param args command line parameters;
	 * @param in the junctions needs to be transformed into string;
	 * @return a string list waiting to be put out;
	 */

	public static ArrayList<String> juncsToArray(InParam args, ArrayList<HashMap<String,JuncInfo>> in, ArrayList<IntervalTree<ReadInfo>> itree, ArrayList<IntervalTree<ReadInfo>> itree_input){
		ArrayList<String> out = new ArrayList<String>();
		ArrayList<String> out_list = new ArrayList<String>();
		boolean ip_flag = args.getInput_file() != null;
		boolean exon_flag = args.getGtf_file() != null;
		exon_flag = false;
		FisherTest fisher_test = new FisherTest(ip_reads + input_reads + 16192);
		StringBuffer theJuncLine = new StringBuffer();
		theJuncLine.append("#Chr\t");
		theJuncLine.append("StartPos\t");
		theJuncLine.append("EndPos\t");
		theJuncLine.append("Strand\t");
		theJuncLine.append("Type\t");
		theJuncLine.append("Exons\t");
		if (exon_flag) {
			theJuncLine.append("StartExon\t");
			theJuncLine.append("IPSEReads\t");
			if (ip_flag) {
				theJuncLine.append("INPUTSEReads\t");
			}
			theJuncLine.append("EndExon\t");
			theJuncLine.append("IPEEReads\t");
			if (ip_flag) {
				theJuncLine.append("INPUTEEReads\t");
			}
			
		}
		theJuncLine.append("JunctionReads\t");
		theJuncLine.append("IP_RPM\t");
		if (ip_flag) {
			theJuncLine.append("INPUTJunctionReads\t");
			theJuncLine.append("INPUT_RPM\t");
			theJuncLine.append("TotalJunctionReads\t");
			theJuncLine.append("Total_RPM\t");
			theJuncLine.append("p-Value\t");
		}
		theJuncLine.append("ReadIDs");
		if (ip_flag) {
			theJuncLine.append("\tINPUTReadIDs");
		}
		theJuncLine.append("\tSingleReads");
		if (ip_flag) {
			theJuncLine.append("\tSingleINPUTReads");
		}
		theJuncLine.append("\tCircRatio");
		out.add(theJuncLine.toString());
		for (int i=0; i < in.size(); ++i) {
			for (Entry<String, JuncInfo> entry : in.get(i).entrySet()) {
				JuncInfo this_junc = entry.getValue();
				ArrayList<String> ids = this_junc.getReadID();
				ArrayList<String> inputids = this_junc.getInputids();
				if (ids.size() + inputids.size() >= sup_read) {
					theJuncLine.setLength(0);
					String chrSym = ExonInfo.chrNumToSymbol(i);
					theJuncLine.append(chrSym);
					theJuncLine.append('\t');
					theJuncLine.append(this_junc.getSP());
					theJuncLine.append('\t');
					theJuncLine.append(this_junc.getEP());
					theJuncLine.append('\t');
					theJuncLine.append(this_junc.getStrand());
					theJuncLine.append('\t');
					if (this_junc.isFix_start_exon() && this_junc.isFix_end_exon()) {
						theJuncLine.append("circRNA");
						theJuncLine.append('\t');
					}
					else if (this_junc.isIntron_flag()){
						theJuncLine.append("ciRNA");
						theJuncLine.append('\t');
					}
					else {
						theJuncLine.append("EIciRNA");
						theJuncLine.append('\t');
					}
					theJuncLine.append(this_junc.getExons().size() / 2);
					theJuncLine.append('\t');
					theJuncLine.append(ids.size());
					theJuncLine.append('\t');
					theJuncLine.append((double) (ids.size() + this_junc.getSingle_ip_reads()) * 1000000.0 / (double) ip_reads);
					theJuncLine.append('\t');
					if (ip_flag) {
						theJuncLine.append(inputids.size());
						theJuncLine.append('\t');
						theJuncLine.append((double) (inputids.size() + this_junc.getSingle_input_reads()) * 1000000.0 / (double) input_reads);
						theJuncLine.append('\t');
						theJuncLine.append(ids.size() + inputids.size());
						theJuncLine.append((double) (ids.size() + inputids.size() + this_junc.getSingle_ip_reads() + this_junc.getSingle_input_reads()) * 1000000.0 / (double) (input_reads + ip_reads));
						theJuncLine.append('\t');
						theJuncLine.append(String.format("%.4f", fisher_test.calpValue(ids.size(), inputids.size(), this_junc.getTR() - ids.size(), this_junc.getInputReads() - inputids.size(), 2)));
						theJuncLine.append('\t');
					}
					int j = 0;
					for (; j < ids.size() - 1; ++j){
						theJuncLine.append(ids.get(j));
						theJuncLine.append(',');
					}
					if (ids.size() > 0) {
						theJuncLine.append(ids.get(j));
					}
					else {
						theJuncLine.append('.');
					}
					if (ip_flag) {
						theJuncLine.append('\t');
						j = 0;
						for (;j < inputids.size() - 1; ++j) {
							theJuncLine.append(inputids.get(j));
							theJuncLine.append(',');
						}
						if (inputids.size() > 0) {
							theJuncLine.append(inputids.get(j));
						}
						else {
							theJuncLine.append('.');
						}
					}
					theJuncLine.append('\t');
					theJuncLine.append(this_junc.getSingle_ip_reads());
					if (ip_flag) {
						theJuncLine.append('\t');
						theJuncLine.append(this_junc.getSingle_input_reads());
					}
					int ip_linear = 0;
					int input_linear = 0;
					Iterator<Node<ReadInfo>> nodes = itree.get(i).overlappers(this_junc.getSP(), this_junc.getSP());
					while (nodes.hasNext()){
						Node<ReadInfo> node = nodes.next();
						ip_linear += node.getValue().getNo_xa();
					}
					nodes = itree.get(i).overlappers(this_junc.getEP(), this_junc.getEP());
					while (nodes.hasNext()){
						Node<ReadInfo> node = nodes.next();
						ip_linear += node.getValue().getNo_xa();
					}
					nodes = itree_input.get(i).overlappers(this_junc.getSP(), this_junc.getSP());
					while (nodes.hasNext()){
						Node<ReadInfo> node = nodes.next();
						input_linear += node.getValue().getNo_xa();
					}
					nodes = itree_input.get(i).overlappers(this_junc.getEP(), this_junc.getEP());
					while (nodes.hasNext()){
						Node<ReadInfo> node = nodes.next();
						input_linear += node.getValue().getNo_xa();
					}
					double ratio = (double) (2 * ids.size() + 2 * inputids.size() + this_junc.getSingle_ip_reads() + this_junc.getSingle_input_reads()) / (double) (ip_linear + input_linear);
					theJuncLine.append('\t');
					theJuncLine.append(ratio);
					out.add(theJuncLine.toString());
					if (!this_junc.isIntron_flag()){
						StringBuffer intron_line = new StringBuffer();
						intron_line.append(chrSym);
						intron_line.append('\t');
						if (this_junc.getIntron()[0] != -1) {
							intron_line.append(this_junc.getIntron()[0]);
						}
						else {
							intron_line.append(this_junc.getExons().get(0) - 5000);
						}
						intron_line.append('\t');
						intron_line.append(this_junc.getExons().get(0) - 1);
						intron_line.append('\t');
						intron_line.append(chrSym);
						intron_line.append('_');
						intron_line.append(this_junc.getSP());
						intron_line.append('_');
						intron_line.append(this_junc.getEP());
						intron_line.append("_intron_");
						if (this_junc.getStrand() == '-'){
							intron_line.append("5ss");
						}
						else {
							intron_line.append("3ss");
						}
						out_list.add(intron_line.toString());
						intron_line.setLength(0);
						intron_line.append(chrSym);
						intron_line.append('\t');
						intron_line.append(this_junc.getExons().get(this_junc.getExons().size() - 1) + 1);
						intron_line.append('\t');
						if (this_junc.getIntron()[1] != Integer.MAX_VALUE) {
							intron_line.append(this_junc.getIntron()[1]);
						}
						else {
							intron_line.append(this_junc.getExons().get(this_junc.getExons().size() - 1) + 5000);
						}
						intron_line.append('\t');
						intron_line.append(chrSym);
						intron_line.append('_');
						intron_line.append(this_junc.getSP());
						intron_line.append('_');
						intron_line.append(this_junc.getEP());
						intron_line.append("_intron_");
						if (this_junc.getStrand() == '-'){
							intron_line.append("3ss");
						}
						else {
							intron_line.append("5ss");
						}
						out_list.add(intron_line.toString());
					}
				}
			}
		}
		FileRW.fileWrite(args.getOut_prefix() + "_circ_intron.bed", out_list);
		return out;
	}
	
	/**
	 * transform junctions in Bed12 format
	 * @param in junctions with key of start and end split by chromosomes
	 * @return a list of string include head to be put out
	 */
	public static ArrayList<String> juncsToBed(ArrayList<HashMap<String,JuncInfo>> in){
		ArrayList<String> out = new ArrayList<String>();
		out.add(Bed12.getHeader());
		for (int chr=0; chr < in.size(); chr++) {
			String chr_symbol = ExonInfo.chrNumToSymbol(chr);
			for (Iterator<Entry<String, JuncInfo>> it = in.get(chr).entrySet().iterator(); it.hasNext();) {
				Entry<String, JuncInfo> entry = it.next();
				JuncInfo the_junc = entry.getValue();
				if (the_junc.getTR() < the_junc.getReadID().size() || the_junc.getInputReads() < the_junc.getInputids().size()) {
					System.out.println(the_junc.getSP() + "\t" + the_junc.getEP());
					System.out.println(the_junc.getTR() + "\t" + the_junc.getReadID().size());
					System.out.println(the_junc.getInputReads() + "\t" + the_junc.getInputids().size());
				}
				if (the_junc.getInputids().size() + the_junc.getReadID().size() >= sup_read) {
					Bed12 record = new Bed12();
					record.setBlock_count(1);
					record.getBlock_sizes().add(the_junc.getEP() - the_junc.getSP());
					record.getBlock_starts().add(0);
					record.setChr(chr_symbol);
					record.setStart(the_junc.getSP());
					record.setEnd(the_junc.getEP());
					StringBuffer genes = new StringBuffer();
					genes.append(the_junc.getGenes().get(0));
					for (int i = 1; i < the_junc.getGenes().size(); ++i) {
						genes.append(',');
						genes.append(the_junc.getGenes().get(i));
					}
					record.setName(genes.toString());
					record.setStrand(the_junc.getStrand());
					record.setThick_start(record.getStart());
					record.setThick_end(record.getEnd());
					record.setScore(the_junc.getSingle_input_reads() + the_junc.getSingle_ip_reads() + the_junc.getReadID().size() + the_junc.getInputids().size());
					out.add(record.toString());
				}
				else {
					it.remove();
				}
			}
		}
		return out;
	}
	
	/**
	 * give out the Type included in both of the two lists 
	 * @param list1 the first list
	 * @param list2 the second list
	 * @return a list of Element in both
	 */
	public static <E> ArrayList<E> getBoth(ArrayList<E> list1, ArrayList<E> list2){
		ArrayList<E> out = new ArrayList<>();
		for (int i=0; i < list1.size(); ++i) {
			if (list2.contains(list1.get(i))) {
				out.add(list1.get(i));
			}
		}
		return out;
	}
	
	/**
	 * give the index of the first integer which is not less than the target and return -1 while the list is null, return 0 while the list is empty
	 * @param target the target number, we want to search the first number not less than it
	 * @param inc_seq an increasing sequence include integers
	 * @return the index of the integer
	 */
	public static int searchMinNoLess(int target, ArrayList<Integer> inc_seq) {
		int out = -1;
		if (inc_seq == null) {
			return -1;
		}
		int l = 0;
		int r = inc_seq.size() - 1;
		if (inc_seq.size() == 0||target <= inc_seq.get(0)) {
			out = 0;
		}
		else if(target <= inc_seq.get(r)){
			int m = 0;
			while (l < r) {
				m = (l + r) >> 1;
				if (l == m) {
					out = r;
					break;
				}
				if (target < inc_seq.get(m)) {
					r = m;
				}
				else if (target > inc_seq.get(m)) {
					l = m;
				}
				else {
					while (target == inc_seq.get(m)) {
						out = m;
						m--;
					}
					break;
				}
			}
		}
		else {
			out = r + 1;
		}
		return out;
	}
	
	/**
	 * print some tips with time
	 * @param prefix the tip of time prefix;
	 */
	public static void printNow(String prefix) {
		Date time = new Date();
		System.out.printf("%s %tF %tT\n", prefix, time, time);
	}
	
	/**
	 * set a int vector to 0
	 * @param vector the vector needs to be set to 0(output);
	 */
	public static void setToZero(int[] vector) {
		for (int i = 0; i < vector.length; ++i) {
			vector[i] = 0;
		}
	}
	
	/**
	 * get junctions in bam file
	 * @param reader a bam file reader
	 * @param out a list of junctions divided into chromosomes(output)
	 * @param itree a tree to store alignment information
	 * @param ip_flag whether this is a ip bam file reader
	 * @param args command line parameters
	 * @param r removing rRNA
	 * @return alignments count
	 */
	private static int filtBam(SamReader reader, ArrayList<HashMap<String,JuncInfo>> out, ArrayList<IntervalTree<ReadInfo>> itree, boolean ip_flag, InParam args, RemoverRNA r) {
		int reads = 0;
		int alignments = 0;
		int map_ids = 0;
		int rrna_ids = -1;
		boolean last_pair = false;
		String thisreadid = null;
		String lastreadid = null;
		String tempString = null;
		String[] first_reads = new String[64];
		String[] last_reads = new String[64];
		int first_length = 0;
		int last_length = 0;
		int filted = 0;
		HandleReads handle = new HandleReads();
		for (SAMRecord samRecord : reader){
			if (samRecord.getReadUnmappedFlag()) {
				continue;
			}
			last_pair = samRecord.getReadPairedFlag() && samRecord.getSecondOfPairFlag();
			tempString = samRecord.getSAMString();
			thisreadid = samRecord.getReadName();
			if (!thisreadid.equals(lastreadid)) {
				lastreadid = thisreadid;
				map_ids++;
				if (first_length + last_length - filted == 0) {
					rrna_ids++;
				}
				handle.filtPCC(first_reads, first_length, out, last_reads, last_length, ip_flag, itree, args.getCirc_length(), r, args.isPair_mode(), args.getCirc_bed() == null, args.isUniq_mode());
				first_length = 0;
				last_length = 0;
				filted = 0;
			}
			reads++;
			if (reads % 1000000 == 0) {
				printNow("Runing " + reads + " alignments at");
			}
			if (!last_pair) {
				first_reads[first_length] = tempString;
				first_length++;
			}
			else {
				last_reads[last_length] = tempString;
				last_length++;
			}
			int alignment = lineToInterval(tempString, itree, r, ip_flag, args.isUniq_mode());
			alignments += alignment;
			if (alignment == 0) {
				filted++;
				continue;
			}
//			if (thisreadid.equals(debugString)) {
//				System.out.println("Debug");
//			}

			
		}
		if (first_length + last_length - filted == 0) {
			rrna_ids++;
		}
		handle.filtPCC(first_reads, first_length, out, last_reads, last_length, ip_flag, itree, args.getCirc_length(), r, args.isPair_mode(), args.getCirc_bed() == null, args.isUniq_mode());
		System.out.println("Total Mapped reads: " + map_ids);
		System.out.println("Other chromosomes or rRNA reads get filted: " + rrna_ids);
		return alignments;
	}
	
	/**
	 * read trim file instead of input file for peakcalling
	 * @param args command line parameters
	 * @param itree a tree to store alignments
	 * @return alignments count
	 */
	public static int readTrim(InParam args, ArrayList<IntervalTree<ReadInfo>> itree){
		int reads = 0;
		String trim_file = args.getTrim_file();
		SamReader reader = null;
		try {
			if (trim_file !=null) {
				RemoverRNA r = new RemoverRNA(args.getRrna_bed());
				setToZero(input_chr_reads);
				itree.clear();
				for (int i = 0; i < 25; ++i) {
					IntervalTree<ReadInfo> temp_tree = new IntervalTree<>();
					temp_tree.setSentinel(null);
					itree.add(temp_tree);
				}
				File fi = new File(trim_file);
				FileRW.printNow("Scaning " + trim_file + " for BSJ at");
				
				reader = SamReaderFactory.makeDefault().open(fi);
				for (SAMRecord samRecord : reader){
					if (samRecord.getReadUnmappedFlag()) {
						continue;
					}
					reads++;
					if (reads % 1000000 == 0) {
						printNow("Runing " + reads + " alignments at");
					}
					String line = samRecord.getSAMString();
					lineToInterval(line, itree, r, false, args.isUniq_mode());
				}
				reader.close();
			}
		}
		catch(IOException e){
			e.printStackTrace();
		}
		finally{
			if (reader != null){
				try{
					reader.close();
				}
				catch(IOException e1){
				}
			}
		}
		if (reads != 0) {
			input_reads = reads;
		}
		return reads;
	}
	
	/**
	 * read txt file for back splicing junctions and put all reads filted into trees
	 * @param args command line parameters;
	 * @param itree a tree to store alignments;
	 * @return a list of junctions divided into chromosomes;
	 */
	public static ArrayList<HashMap<String,JuncInfo>> filtTxt(InParam args, ArrayList<IntervalTree<ReadInfo>> itree) {
		ArrayList<HashMap<String,JuncInfo>> out = creatJuncTable();
		int reads = 0;
		boolean last_pair = false;
		boolean unmap = false;
		String thisreadid = null;
		String lastreadid = null;
		String tempString = null;
		RemoverRNA r = new RemoverRNA(args.getRrna_bed());
		String[] first_reads = new String[64];
		String[] last_reads = new String[64];
		int first_length = 0;
		int last_length = 0;
		HandleReads handle = new HandleReads();
		File in = new File(args.getIp_file());
		BufferedReader reader = null;
		try {
			reader = new BufferedReader(new FileReader(in));
			while ((tempString = reader.readLine()) != null){
				reads++;
				if (reads % 1000000 == 0) {
					printNow("Runing " + reads + " reads at");
				}
				String[] cols = tempString.split("\t");
				int flags = Integer.parseInt(cols[1]);
				unmap = (flags & 0x04) != 0;
				if (unmap) {
					continue;
				}
				last_pair = (flags & 0x80) != 0;
				thisreadid = cols[0];
				int filted = lineToInterval(tempString, itree, r, true, true);
				if (filted == 0) {
					continue;
				}
				if (!thisreadid.equals(lastreadid)) {
					lastreadid = thisreadid;
					handle.filtPCC(first_reads, first_length, out, last_reads, last_length, true, itree, args.getCirc_length(), r, args.isPair_mode(), args.getCirc_bed() == null, args.isUniq_mode());
					first_length = 0;
					last_length = 0;
				}
				if (!last_pair) {
					first_reads[first_length] = tempString;
					first_length++;
				}
				else {
					last_reads[last_length] = tempString;
					last_length++;
				}
			}
			handle.filtPCC(first_reads, first_length, out, last_reads, last_length, true, itree, args.getCirc_length(), r, args.isPair_mode(), args.getCirc_bed() == null, args.isUniq_mode());
			reader.close();
		}
		catch(IOException e){
			e.printStackTrace();
		}
		finally{
			if (reader != null){
				try{
					reader.close();
				}
				catch(IOException e1){
				}
			}
		}
		return out;
	}
	
	private static double calP_ValueBack(FisherTest fisher_test, int back, int chr, int start, int end, IntervalTree<ReadInfo> itree, IntervalTree<ReadInfo> itree_input, boolean dev_flag, boolean linear_flag){
		double p_value = 1.0;
		int ip_read = 0;
		int input_read = 0;
		Iterator<Node<ReadInfo>> nodes = itree.overlappers(start, end);
		while (nodes.hasNext()){
			Node<ReadInfo> node = nodes.next();
			if (!dev_flag || (node.getStart() >= start - halfDev && node.getEnd() <= end + halfDev)){
				if (linear_flag){
					ip_read += node.getValue().getLinear();
				}
				else {
					ip_read += node.getValue().getNo_xa();
				}
			}
		}
		nodes = itree_input.overlappers(start, end);
		while (nodes.hasNext()){
			Node<ReadInfo> node = nodes.next();
			if (!dev_flag || (node.getStart() >= start - halfDev && node.getEnd() <= end + halfDev)){
				if (linear_flag){
					input_read += node.getValue().getLinear();
				}
				else {
					input_read += node.getValue().getNo_xa();
				}
			}
		}
		if (back == 0) {
			p_value = fisher_test.calpValue(ip_read, input_read, ip_reads - ip_read, input_reads - input_read, 2);
		}
		else if (back < 0) {
			p_value = fisher_test.calpValue(ip_read, input_read, ip_chr_reads[chr] - ip_read, input_chr_reads[chr] - input_read, 2);
		}
		else {
			int ip_back = 0;
			int input_back = 0;
			nodes = itree.overlappers(start - back / 2, end + back / 2);
			while(nodes.hasNext()) {
				Node<ReadInfo> node = nodes.next();
				if (linear_flag){
					ip_back += node.getValue().getLinear();
				}
				else {
					ip_back += node.getValue().getNo_xa();
				}
			}
			nodes = itree_input.overlappers(start - back / 2, end + back / 2);
			while(nodes.hasNext()) {
				Node<ReadInfo> node = nodes.next();
				if (linear_flag){
					input_back += node.getValue().getLinear();
				}
				else {
					input_back += node.getValue().getNo_xa();
				}
			}
			p_value = fisher_test.calpValue(ip_read, input_read, ip_back - ip_read, input_back - input_read, 2);
		}
		return p_value;
	}
	
	/**
	 * using ip and input informations for peakcalling
	 * @param args command line parameters;
	 * @param itree a tree of ip alignments information;
	 * @param itree_input a tree of input alignments information;
	 * @param juncs the junctions list
	 * @param genes the tree of gtf file gene and exon information;
	 * @param chr_lengths a list of all 25 chromosome lengths;
	 */
	public static void calPeak(InParam args, ArrayList<IntervalTree<ReadInfo>> itree, ArrayList<IntervalTree<ReadInfo>> itree_input, ArrayList<HashMap<String,JuncInfo>> juncs, ArrayList<IntervalTree<ArrayList<Gene>>> genes, ArrayList<Integer> chr_lengths){
		if (ip_reads == 0 || input_reads == 0) {
			return;
		}
		FisherTest fisher_test = new FisherTest(ip_reads + input_reads + 16192);
		int window_size = args.getWindow_size();
		double p_threshold = args.getP_value();
		ArrayList<String> out_put = new ArrayList<>();
		String peak_file = args.getOut_prefix() + "_linear_peak.bed";
		String peak_dev = args.getOut_prefix() + "_dev.bed";
		String drop_file = args.getOut_prefix() + "_drop.dev";
		String circ_high_file = args.getOut_prefix() + "_circ_peak_high.bed";
		String circ_mid_file = args.getOut_prefix() + "_circ_peak_mid.bed";
		String circ_low_file = args.getOut_prefix() + "_circ_peak_low.bed";
		out_put.add(Bed12.getHeader() + "\tMethlaytionPercent\tCirc");
		fileWrite(peak_file, out_put);
		out_put.set(0, Bed12.getHeader() + "\tMethlaytionPercent\tStat");
		fileWrite(circ_high_file, out_put);
		fileWrite(circ_mid_file, out_put);
		fileWrite(circ_low_file, out_put);
		if (args.isRetain_test()) {
			fileWrite(peak_dev, out_put);
			fileWrite(drop_file, out_put);
		}
		out_put.clear();
		for(int chr = 0; chr < itree.size(); chr++) {
			String chr_symbol = ExonInfo.chrNumToSymbol(chr);
			int chr_length = chr_lengths.get(chr);
			int seg_start = 0;
			int seg_end = 0;
			double total_p_value = 0.0;
			boolean last_p = false;
			IntervalTree<Double> peak_tree = new IntervalTree<>();
			IntervalTree<JuncInfo> circ_tree = new IntervalTree<>();
			peak_tree.setSentinel(null);
			peak_tree.setSentinel(null);
			ArrayList<String> circ_high = new ArrayList<>();
			ArrayList<String> circ_mid = new ArrayList<>();
			ArrayList<String> circ_low = new ArrayList<>();
			for (Entry<String, JuncInfo> entry : juncs.get(chr).entrySet()) {
				int start = entry.getValue().getSP();
				int end = entry.getValue().getEP();
				int max_window = (end - start) / window_size + 1;
				double p_mean = 0.0;
				int start_windows = 0;
				int end_windows = 0;
				for (int i = 0; i <= max_window; ++i){
					double p_value = calP_ValueBack(fisher_test, args.getBackground_size(), chr, start + window_size * i, start + window_size * (i + 1) - 1, itree.get(chr), itree_input.get(chr), true, false);
					if (p_value < p_threshold) {
						p_mean += p_value;
						++start_windows;
					}
					else {
						break;
					}
				}
				if (start_windows >= max_window - 1 || start_windows == 0){
					continue;
				}
				for (int i = 0; i <= max_window; ++i){
					double p_value = calP_ValueBack(fisher_test, args.getBackground_size(), chr, end - window_size * (i + 1) + 1, end - window_size * i, itree.get(chr), itree_input.get(chr), true, false);
					if (p_value < p_threshold) {
						p_mean += p_value;
						++end_windows;
					}
					else {
						break;
					}
				}
				if (start_windows + end_windows >= args.getPeak_length() / window_size && end_windows != 0){
					Bed12 record = new Bed12();
					record.setChr(chr_symbol);
					record.setStrand(entry.getValue().getStrand());
					record.setScore(p_mean / (start_windows + end_windows));
					record.setBlock_count(2);
					StringBuffer name = new StringBuffer();
					name.append(entry.getValue().getGenes().get(0));
					for (int i = 1; i < entry.getValue().getGenes().size(); ++i) {
						name.append(':');
						name.append(entry.getValue().getGenes().get(i));
					}
					name.append(':');
					name.append(start);
					name.append('-');
					name.append(end);
					record.setName(name.toString().toString());
					record.setStart(start);
					record.setThick_start(start);
					record.getBlock_starts().add(0);
					record.getBlock_sizes().add(window_size * start_windows);
					circ_tree.put(start, start + window_size * start_windows - 1, null);
					record.setEnd(end);
					record.setThick_end(end);
					record.getBlock_starts().add(end - window_size * end_windows + 1 - start);
					record.getBlock_sizes().add(window_size * end_windows);
					circ_tree.put(end - window_size * end_windows + 1, end, null);
					int ip_read = 0;
					int input_read = 0;
					Iterator<Node<ReadInfo>> nodes = itree.get(chr).overlappers(start, start + window_size * start_windows - 1);
					while (nodes.hasNext()){
						Node<ReadInfo> node = nodes.next();
						if (node.getStart() >= start - halfDev && node.getEnd() <= end + halfDev){
							ip_read += node.getValue().getNo_xa();
						}
					}
					nodes = itree_input.get(chr).overlappers(start, start + window_size * start_windows - 1);
					while (nodes.hasNext()){
						Node<ReadInfo> node = nodes.next();
						if (node.getStart() >= start - halfDev && node.getEnd() <= end + halfDev){
							input_read += node.getValue().getNo_xa();
						}
					}
					nodes = itree.get(chr).overlappers(end - window_size * end_windows + 1, end);
					while (nodes.hasNext()){
						Node<ReadInfo> node = nodes.next();
						if (node.getStart() >= start - halfDev && node.getEnd() <= end + halfDev){
							ip_read += node.getValue().getNo_xa();
						}
					}
					nodes = itree_input.get(chr).overlappers(end - window_size * end_windows + 1, end);
					while (nodes.hasNext()){
						Node<ReadInfo> node = nodes.next();
						if (node.getStart() >= start - halfDev && node.getEnd() <= end + halfDev){
							input_read += node.getValue().getNo_xa();
						}
					}
					record.getAdd_info().append('\t');
					record.getAdd_info().append(Math.exp(Math.log(ip_read) + Math.log(input_reads) - Math.log(input_read) - Math.log(ip_reads)));
					boolean strict = true;
					double p_value = calP_ValueBack(fisher_test, args.getBackground_size(), chr, start - window_size, start - 1, itree.get(chr), itree_input.get(chr), false, false);
					strict &= p_value >= p_threshold;
					p_value = calP_ValueBack(fisher_test, args.getBackground_size(), chr, end + 1, start + window_size, itree.get(chr), itree_input.get(chr), false, false);
					strict &= p_value >= p_threshold;
					if (strict){
						record.getAdd_info().append("\tstrict");
					}
					else{
						record.getAdd_info().append("\tloose");
					}
					if (entry.getValue().isIp_junc()) {
						circ_high.add(record.toString());
					}
					else if (entry.getValue().getSingle_ip_reads() > 0) {
						circ_mid.add(record.toString());
					}
					else {
						circ_low.add(record.toString());
					}
				}
				Iterator<Node<ReadInfo>> nodes = itree.get(chr).overlappers(start + halfDev, start + halfDev);
				while (nodes.hasNext()) {
					Node<ReadInfo> node = nodes.next();
					if (node.getStart() >= start - halfDev){
						node.getValue().calLinear(true);
					}
				}
				nodes = itree_input.get(chr).overlappers(start + halfDev, start + halfDev);
				while (nodes.hasNext()) {
					Node<ReadInfo> node = nodes.next();
					if (node.getStart() >= start - halfDev){
						node.getValue().calLinear(true);
					}
				}
				nodes = itree.get(chr).overlappers(end - halfDev, end - halfDev);
				while (nodes.hasNext()) {
					Node<ReadInfo> node = nodes.next();
					if (node.getStart() >= end + halfDev){
						node.getValue().calLinear(false);
					}
				}
				nodes = itree_input.get(chr).overlappers(end - halfDev, end - halfDev);
				while (nodes.hasNext()) {
					Node<ReadInfo> node = nodes.next();
					if (node.getStart() >= end + halfDev){
						node.getValue().calLinear(false);
					}
				}
			}
			ArrayList<String> out_dev = new ArrayList<>();
			for (int i = 0; i < chr_length; i += window_size) {
				double p_value = calP_ValueBack(fisher_test, args.getBackground_size(), chr, i, i + window_size - 1, itree.get(chr), itree_input.get(chr), false, true);
				if (p_value < p_threshold) {
					total_p_value += p_value;
					if (!last_p) {
						seg_start = i / window_size;
					}
					last_p = true;
					seg_end = i / window_size;
				}
				else {
					double ava = total_p_value / (seg_end - seg_start +1);
					if (last_p) {
						peak_tree.put(seg_start, seg_end, ava);
						out_dev.add(chr_symbol + "\t" + (seg_start * window_size) + "\t" + (seg_end * window_size + window_size));
					}
					last_p = false;
					total_p_value = 0.0;
				}
			}
			fileAppend(circ_high_file, circ_high);
			fileAppend(circ_mid_file, circ_mid);
			fileAppend(circ_low_file, circ_low);
			fileAppend(peak_file, out_put);
			if (args.isRetain_test()) {
				fileAppend(peak_dev, out_dev);
			}
			out_dev.clear();
			out_put.clear();
			HashMap<String, Bed12> junc_peaks = new HashMap<>();
			Iterator<Node<ArrayList<Gene>>> itera = genes.get(chr).iterator();
			HashMap<Node<Double>, Bed12> drop_nodes = new HashMap<>();
			while (itera.hasNext()) {
				Node<ArrayList<Gene>> gene_node = itera.next();
				for (int g = 0; g < gene_node.getValue().size(); g++) {
					ArrayList<Transcript> scripts = gene_node.getValue().get(g).getTranscripts();
					for (int i = 0; i < scripts.size(); ++i) {
						Bed12 record = null;
						HashMap<Node<Double>, Bed12> drop = null;
						String[] keys = new String[64];
						int key_index = 0;
						int peak_length = 0;
						boolean junc_flag = false;
						boolean junc_record = false;
						ArrayList<ExonInfo> exons = scripts.get(i).getExons();
						for (int j = 0; j < exons.size(); ++j) {
							int start = exons.get(j).getStart_position() / window_size;
							int end = exons.get(j).getEnd_position() / window_size;
							Iterator<Node<Double>> nodes = peak_tree.overlappers(start, end);
							boolean over_flag = false;
							while (nodes.hasNext()) {
								over_flag = true;
								Node<Double> node = nodes.next();
								if (node.getStart() == start && junc_flag) {
									drop.put(node, null);
									junc_record = true;
									String key = record.getEnd() + "\t" + start;
									keys[key_index] = key;
									key_index++;
									record.setEnd(Math.min(end, node.getEnd()));
									record.setThick_end(node.getEnd());
									record.setBlock_count(record.getBlock_count() + 1);
									record.getBlock_sizes().add((Math.min(end, node.getEnd()) - start + 1) * window_size);
									record.getBlock_starts().add((start - record.getStart()) * window_size);
									int this_length = Math.min(end, node.getEnd()) - start + 1;
									record.setScore(record.getScore() + node.getValue() * this_length);
									peak_length += this_length;
								}
								else {
									if (peak_length >= 4 && junc_record) {
										record.setThick_start(record.getStart() * window_size);
										record.setThick_end(record.getEnd() * window_size);
										for (int k = 0; k < key_index; ++k) {
											String key = keys[k];
											if (junc_peaks.containsKey(key)) {
												if (junc_peaks.get(key).getBlock_count() < record.getBlock_count()) {
													junc_peaks.put(key, record);
													record.getIds().add(scripts.get(i).getId());
												}
												else if (junc_peaks.get(key).getBlock_count() == record.getBlock_count()) {
													junc_peaks.get(key).getIds().add(scripts.get(i).getId());
												}
											}
											else {
												record.getIds().add(scripts.get(i).getId());
												junc_peaks.put(key, record);
											}
										}
										drop_nodes.putAll(drop);
										record.setStart(record.getThick_start());
										record.setEnd(record.getThick_end());
										record.setScore(record.getScore() / peak_length);
									}
									drop = new HashMap<>();
									record = new Bed12();
									key_index = 0;
									record.setChr(chr_symbol);
									record.setStrand(scripts.get(i).getStrand());
									record.setStart(Math.max(start, node.getStart()));
									record.setEnd(Math.min(end, node.getEnd()));
									record.setThick_start(node.getStart());
									record.setThick_end(node.getEnd());
									record.setBlock_count(1);
									record.getBlock_sizes().add((record.getEnd() - record.getStart() + 1) * window_size);
									record.getBlock_starts().add(0);
									peak_length = record.getEnd() - record.getStart() + 1;
									record.setScore(node.getValue() * peak_length);
									junc_record = false;
								}
								if (junc_flag = node.getEnd()==end) {
									drop.put(node, null);
								}
							}
							if (!over_flag) {
								junc_flag = false;
							}
						}
						if (peak_length >= 4 && junc_record) {
							record.setThick_start(record.getStart() * window_size);
							record.setThick_end(record.getEnd() * window_size);
							for (int k = 0; k < key_index; ++k) {
								String key = keys[k];
								if (junc_peaks.containsKey(key)) {
									if (junc_peaks.get(key).getBlock_count() < record.getBlock_count()) {
										junc_peaks.put(key, record);
										record.getIds().add(scripts.get(i).getId());
									}
									else if (junc_peaks.get(key).getBlock_count() == record.getBlock_count()) {
										junc_peaks.get(key).getIds().add(scripts.get(i).getId());
									}
								}
								else {
									record.getIds().add(scripts.get(i).getId());
									junc_peaks.put(key, record);
								}
							}
							drop_nodes.putAll(drop);
							record.setStart(record.getThick_start());
							record.setEnd(record.getThick_end());
							record.setScore(record.getScore() / peak_length);
						}
					}
				}
			}
			for (Entry<Node<Double>, Bed12> entry : drop_nodes.entrySet()) {
				Node<Double> node = entry.getKey();
				peak_tree.remove(node.getStart(), node.getEnd());
			}
			drop_nodes = new HashMap<>();
			itera = genes.get(chr).iterator();
			while (itera.hasNext()) {
				Node<ArrayList<Gene>> gene_node = itera.next();
				Iterator<Node<Double>> nodes = peak_tree.overlappers(gene_node.getStart(), gene_node.getEnd());
				while(nodes.hasNext()) {
					Node<Double> node = nodes.next();
					int start = Math.max(gene_node.getStart(), node.getStart());
					int end = Math.min(gene_node.getEnd(), node.getEnd());
					if (end - start >= 3) {
						Bed12 record = new Bed12();
						record.setChr(chr_symbol);
						record.setStrand(gene_node.getValue().get(0).getStrand());
						record.setStart(start * window_size);
						record.setEnd(end * window_size);
						record.setName(gene_node.getValue().get(0).getGene_symbol() + ":");
						record.setScore(node.getValue() / (end - start + 1));
						record.setThick_start(node.getStart());
						record.setThick_end(node.getEnd());
						record.setBlock_count(1);
						record.getBlock_sizes().add(record.getEnd() - record.getStart() + window_size);
						record.getBlock_starts().add(0);
						if (!drop_nodes.containsKey(node) || drop_nodes.get(node).getEnd() - drop_nodes.get(node).getStart() > end - start) {
							drop_nodes.put(node, record);
						}
					}
				}
			}
			for (Entry<Node<Double>, Bed12> entry : drop_nodes.entrySet()) {
				Bed12 record = entry.getValue();
				record.setEnd(record.getEnd() + window_size);
				record.setName(record.getName() + ":" + record.getStart() + "-" + record.getEnd());
				record.setThick_end(record.getEnd());
				int start = record.getStart();
				int end = record.getEnd();
				int ip_read = 0;
				int input_read = 0;
				Iterator<Node<ReadInfo>> count_nodes = itree.get(chr).overlappers(start, end);
				while (count_nodes.hasNext()){
					Node<ReadInfo> node = count_nodes.next();
					ip_read += node.getValue().getLinear();
				}
				count_nodes = itree_input.get(chr).overlappers(start, end);
				while (count_nodes.hasNext()){
					Node<ReadInfo> node = count_nodes.next();
					input_read += node.getValue().getLinear();
				}
				record.getAdd_info().append('\t');
				record.getAdd_info().append(Math.exp(Math.log(ip_read) + Math.log(input_reads) - Math.log(input_read) - Math.log(ip_reads)));
				Iterator<Node<JuncInfo>> nodes = circ_tree.overlappers(start, end);
				if (nodes.hasNext()){
					record.getAdd_info().append("\tshare");
				}
				else{
					record.getAdd_info().append("\tuniq");
				}
				out_put.add(record.toString());
			}
			fileAppend(peak_file, out_put);
			out_put.clear();
			HashSet<Bed12> no_repeat = new HashSet<>();
			for (Entry<String, Bed12> entry : junc_peaks.entrySet()) {
				if (no_repeat.add(entry.getValue())) {
					Bed12 record = entry.getValue();
					record.idsToName();
					record.setEnd(record.getEnd() + window_size);
					record.setThick_end(record.getEnd());
					int ip_read = 0;
					int input_read = 0;
					boolean shared = false;
					for (int i = 0; i < record.getBlock_count(); ++i){
						int start = record.getStart() + record.getBlock_starts().get(i);
						int end = start + record.getBlock_sizes().get(i) - 1;
						Iterator<Node<ReadInfo>> count_nodes = itree.get(chr).overlappers(start, end);
						while (count_nodes.hasNext()){
							Node<ReadInfo> node = count_nodes.next();
							ip_read += node.getValue().getLinear();
						}
						count_nodes = itree_input.get(chr).overlappers(start, end);
						while (count_nodes.hasNext()){
							Node<ReadInfo> node = count_nodes.next();
							input_read += node.getValue().getLinear();
						}
						Iterator<Node<JuncInfo>> nodes = circ_tree.overlappers(start, end);
						shared |= nodes.hasNext();
					}
					record.getAdd_info().append('\t');
					record.getAdd_info().append(Math.exp(Math.log(ip_read) + Math.log(input_reads) - Math.log(input_read) - Math.log(ip_reads)));
					if (shared){
						record.getAdd_info().append("\tshare");
					}
					else{
						record.getAdd_info().append("\tuniq");
					}
					out_put.add(record.toString());
				}
			}
			fileAppend(peak_file, out_put);
			out_put.clear();
		}
	}
	
	/**
	 * creat a list of size 25 to get backjunctions
	 * @return the list 
	 */
	private static ArrayList<HashMap<String,JuncInfo>> creatJuncTable(){
		ArrayList<HashMap<String,JuncInfo>> out = new ArrayList<HashMap<String,JuncInfo>>();
		for (int i = 0; i < 25; ++i){
			HashMap<String,JuncInfo> temp = new HashMap<String,JuncInfo>();
			out.add(temp);
		}
		return out;
	}
	
	/**
	 * to file all junctions through gene information
	 * @param junc_map a list of junctions of 25 chromosomes
	 * @param genes trees of gene information of 25 chromosomes
	 */
	private static void filtJuncTable(ArrayList<HashMap<String,JuncInfo>> junc_map, ArrayList<IntervalTree<ArrayList<Gene>>> genes) {
		int count = 0;
		for (int i=0; i < junc_map.size(); ++i) {
			junc_map.set(i, filtJuncTable(junc_map.get(i), genes.get(i)));
			count += junc_map.get(i).size();
		}
		System.out.println("BSJ with in gene: " + count);
	}
	
	/**
	 * give the junctions filted by gene information in a chromosome
	 * @param junc_map junctions in this chromosome;
	 * @param genes tree of gene information of a chromosome;
	 * @return new junctions filted of these junctions
	 */
	private static HashMap<String,JuncInfo> filtJuncTable(HashMap<String,JuncInfo> junc_map, IntervalTree<ArrayList<Gene>> genes) {
		HashMap<String,JuncInfo> replace = new HashMap<>();
		for (Entry<String, JuncInfo> entry : junc_map.entrySet()) {
			JuncInfo the_junc = entry.getValue();
			int start = the_junc.getSP();
			int end = the_junc.getEP();
			int gene_length = 0;
			ArrayList<Integer> exons = new ArrayList<>();
			int[] intron = {-1, Integer.MAX_VALUE};
			Iterator<Node<ArrayList<Gene>>> nodes = genes.overlappers(start, end);
			while (nodes.hasNext()) {
				Node<ArrayList<Gene>> node = nodes.next();
				if (node.getEnd() >= end - halfDev && node.getStart() <= start + halfDev) {
					if (node.getEnd() - node.getStart() > gene_length) {
						gene_length = node.getEnd() - node.getStart();
						the_junc.setStrand(node.getValue().get(0).getStrand());
					}
					for(int i=0; i < node.getValue().size(); ++i) {
						the_junc.addGene(node.getValue().get(i).getGene_symbol());
						int[] introns = {-1, Integer.MAX_VALUE};
						ArrayList<Integer> temp = node.getValue().get(i).scriptExons(the_junc, halfDev, introns);
						if (temp.size() > exons.size()) {
							exons = temp;
							intron[0] = introns[0];
							intron[1] = introns[1];
						}
					}
					replace.put(entry.getKey(), the_junc);
				}
			}
			the_junc.setIntron_flag(exons.size() == 0);
			the_junc.setExons(exons);
			the_junc.setIntron(intron);
		}
		return replace;
	}
	
	/**
	 * put an alignment into the tree
	 * @param line the alignment;
	 * @param itree the trees to store information;
	 * @param r removing rRNA regions;
	 * @param ip_flag whether the alignment is in IP file;
	 * @return whether this alignment is in rRNA regions
	 */
	private static int lineToInterval(String line, ArrayList<IntervalTree<ReadInfo>> itree, RemoverRNA r, boolean ip_flag, boolean uniq_map) {
		int out = 0;
		if (itree == null) {
			return out;
		}
		String[] cols = line.split("\t");
		
		int chr_num = ExonInfo.chrSymbolToNum(cols[2]);
		int xa_index = line.indexOf("XA:Z:");
		if (chr_num >= 0 && chr_num < itree.size()) {
			int start = Integer.parseInt(cols[3]);
			int end = ExonInfo.getEnd(start, cols[5]); 
			if (!r.isrRNA(chr_num, start, end)) {
				++out;
				ReadInfo old = itree.get(chr_num).put(start, end, null);
				ReadInfo value = old;
				if (value == null) {
					value = new ReadInfo();
				}
				if (ip_flag) {
					++ip_chr_reads[chr_num];
				}
				else {
					++input_chr_reads[chr_num];
				}
				if (uniq_map && xa_index == -1) {
					value.incNo_xa();
					itree.get(chr_num).put(start, end, value);
				}
				else {
					value.incNo_xa();
					itree.get(chr_num).put(start, end, value);
				}
			}
		}
		if (!uniq_map && xa_index != -1) {
			String xa = line.substring(xa_index);
			int last_col = xa.indexOf("\t");
			if (last_col == -1) {
				xa = xa.substring(xa.lastIndexOf(':') + 1);
			}
			else {
				xa = xa.substring(0, last_col);
				xa = xa.substring(xa.lastIndexOf(':') + 1);
			}
			String[] reads = xa.split(";");
			for (int i = 0; i < reads.length; ++i) {
				String[] xa_cols = reads[i].split(",");
				chr_num = ExonInfo.chrSymbolToNum(xa_cols[0]);
				if (chr_num >= 0 && chr_num < itree.size()) {
					int start = Integer.parseInt(xa_cols[1]);
					if (start < 0) {
						start = -start;
					}
					int end = ExonInfo.getEnd(start, xa_cols[2]);
					if (!r.isrRNA(chr_num, start, end)) {
						++out;
						if (ip_flag) {
							++ip_chr_reads[chr_num];
						}
						else {
							++input_chr_reads[chr_num];
						}
						ReadInfo old = itree.get(chr_num).put(start, end, null);
						ReadInfo value = old;
						if (value == null) {
							value = new ReadInfo();
						}
						value.incNo_xa();
						itree.get(chr_num).put(start, end, value);
					}
				}
			}
		}
		return out;
	}
	
	/**
	 * counting single end support reads to junctions in a tree 
	 * @param itree trees that stored alignments;
	 * @param juncs junctions found;
	 * @param ip_flag whether this is a tree of IP file;
	 */
	private static void countSamfile(ArrayList<IntervalTree<ReadInfo>> itree,  ArrayList<HashMap<String,JuncInfo>> juncs, boolean ip_flag) {
		for (int i = 0; i < juncs.size(); ++i) {
			for (Entry<String, JuncInfo> entry : juncs.get(i).entrySet()) {
				JuncInfo junc = entry.getValue();
				Iterator<Node<ReadInfo>> nodes = itree.get(i).overlappers(junc.getSP() - halfDev, junc.getSP() + halfDev);
				while(nodes.hasNext()) {
					Node<ReadInfo> node = nodes.next();
					ReadInfo value = node.getValue();
					junc.addReads(value.getNo_xa(), ip_flag);
					if (node.getStart() >= junc.getSP() - halfDev) {
						if (ip_flag) {
							junc.setSingle_ip_reads(junc.getSingle_ip_reads() + value.getFront());
						}
						else {
							junc.setSingle_input_reads(junc.getSingle_input_reads() + value.getFront());
						}
					}
				}
				nodes = itree.get(i).overlappers(junc.getEP() - halfDev, junc.getEP() + halfDev);
				while(nodes.hasNext()) {
					Node<ReadInfo> node = nodes.next();
					ReadInfo value = node.getValue();
					if (node.getStart() > junc.getSP() + halfDev) {
						junc.addReads(value.getNo_xa(), ip_flag);
					}
					if (node.getEnd() <= junc.getEP() + halfDev) {
						if (ip_flag) {
							junc.setSingle_ip_reads(junc.getSingle_ip_reads() + value.getBehind());
						}
						else {
							junc.setSingle_input_reads(junc.getSingle_input_reads() + value.getBehind());
						}
					}
				}
			}
		}
	}
	
}
