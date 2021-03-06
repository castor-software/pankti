#!/usr/bin/python
# -*- coding: utf-8 -*-
# Filename: diff-descartes-report.py

import csv
import sys
import os
import argparse
import logging
import json
import copy

def get_arguments():
    parser = argparse.ArgumentParser(description="Check the classification of invoked methods based on descartes report",
            formatter_class=argparse.RawDescriptionHelpFormatter,
            epilog="python diff-descartes-report.py -i <path/to/invoked-methods.csv> -d <path/to/methods.json> -o <path/to/output.csv>")

    parser.add_argument("-i", "--invoked_methods", required=True,
            help="the path to invoked-methods.csv")
    parser.add_argument("-d", "--descartes_report", action="append",
            help="the path to methods.json generated by descartes (can be specified multiple times)")
    parser.add_argument("--descartes_folder",
            help="the path to a folder that contains all methods.json generated by descartes")
    parser.add_argument("-o", "--output", default="./invoked-methods-result.csv",
            help="the path to the output csv file (default: ./invoked-methods-result.csv)")
    args = parser.parse_args()

    if not args.descartes_report and not args.descartes_folder:
        print("Error: either --descartes_report or --descartes_folder should be specified!")
        print("")
        parser.print_help()
        sys.exit(1)

    return args

# return (headers, rows)
def read_from_csv(path):
    with open(path) as f:
        f_csv = csv.DictReader(f)
        return f_csv.fieldnames, list(f_csv)

def write2csv(path, headers, rows):
    with open(path, 'w') as file:
        f_csv = csv.DictWriter(file, headers)
        f_csv.writeheader()
        f_csv.writerows(rows)

def walk_descartes_folder(path):
    report_file_name = "methods.json"
    report_files = list()
    for root, dirs, files in os.walk(path):
        for file in files:
            if file == report_file_name: report_files.append(os.path.join(root, file))

    return report_files

def main():
    args = get_arguments()

    # load invoked-methods.csv
    headers, rows = read_from_csv(args.invoked_methods)
    logging.info("There are %d methods in the csv under analysis"%len(rows))
    headers.extend(["classification-after"])

    # load methods.json
    report_files = list()
    if args.descartes_report: report_files.extend(args.descartes_report)
    if args.descartes_folder: report_files.extend(walk_descartes_folder(args.descartes_folder))
    methods_list = list()
    for json_file in report_files:
        with open(json_file, 'rt') as file:
            descartes_report = json.load(file)
            methods_list.extend(descartes_report["methods"])
            logging.info("Loading %d methods from %s"%(len(descartes_report["methods"]), json_file))

    # construct a dictionary whose key:value is: class-and-name:classification
    methods_classification = dict()
    for method in methods_list:
        full_name = "%s/%s/%s"%(method["package"], method["class"], method["name"])
        full_name = full_name.replace("/", ".")
        methods_classification[full_name] = method["classification"]

    result = list()
    improvement = {
        "pseudo-tested-before": 0,
        "not-covered-after": 0,
        "pseudo-tested-after": 0,
        "partially-tested-after": 0,
        "tested-after": 0
    }
    for row in rows:
        key = "%s.%s"%(row["parent-FQN"], row["method-name"])
        classification_after = methods_classification[key]
        row["classification-after"] = classification_after if classification_after != "not-covered" else row["classification"]
        result.append(copy.copy(row))

        improvement["%s-before"%row["classification"]] = improvement["%s-before"%row["classification"]] + 1
        improvement["%s-after"%classification_after] = improvement["%s-after"%classification_after] + 1

    # write result to output file
    write2csv(args.output, headers, result)

    logging.info("Analysis finished")
    print(improvement)

if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    main()