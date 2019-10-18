import sets
import sys, os

UNSAFE_ONLINE_UPDATE=0
SAFE_ONLINE_UPDATE=1
FATAL_MODEL_CHANGES=2
MODELS_SAME=3

class ModelDiffer:

    def __init__(self, current_dict, past_dict):

        self.final_changed_model=dict()
        self.current_dict = current_dict
        self.past_dict = past_dict
        self.set_current = sets.Set()
        self.set_past = sets.Set()
        for item in self.current_dict.keys():
            self.set_current.add(item)
        for item in self.past_dict.keys():
            self.set_past.add(item)
        self.intersect = self.set_current.intersection(self.set_past)

    def added(self):
        return self.set_current - self.intersect

    def removed(self):
        return self.set_past - self.intersect

    def changed(self):
        result = sets.Set()
        for o in self.intersect:
            if self.past_dict[o] != self.current_dict[o]:
                result.add(o)
        return result

    def unchanged(self):
        result = sets.Set()
        for o in self.intersect:
            if self.past_dict[o] == self.current_dict[o]:
                result.add(o)
        return result

    def print_diff(self,s, category):
        print category
        if len(s) > 0:
            print s

    def recursive_changed_detail(self, key, token, root):
        debug("DEBUG: Entering recursive_changed_detail key=%s token=%s root=%s", key, token, root)
        a=ModelDiffer(self.current_dict[key], self.past_dict[key])
        diff=a.changed()
        added=a.added()
        removed=a.removed()
        saved_token=token
        debug('DEBUG: In recursive changed detail %s', diff)
        debug('DEBUG: In recursive added detail %s', added)
        if len(diff) > 0:
            for o in diff:
                token=saved_token
                # The token is a dotted string that is used to parse and rebuilt the structure later
                debug('DEBUG: in recursive changed detail walking down 1 %s', o)
                token=token+'.'+o
                if a.is_dict(o):
                    debug('DEBUG: in recursive changed detail walking down 2 %s', token)
                    a.recursive_changed_detail(o,token, root)
                    last=token.rfind('.')
                    token=root
                else:
                    all_changes.append(token)
                    last=token.rfind('.')
                    token=root


        # already out of recursive calls, add all entries from current dictionary
        # resources.JDBCSubsystemResources.* (note it may not have the lower level nodes
        added_token=token
        debug('DEBUG: current added token %s' , added_token)
        if len(added) > 0:
            for item in added:
                token=saved_token
                debug('DEBUG: recursive added token %s item %s ', token, item)
                all_added.append(token + '.' + item)

        # We don't really care about this, just put something here is enough

        if len(removed) > 0:
            for item in removed:
                debug('DEBUG: removed %s', item)
                all_removed.append(token + '.' + item)
        debug('DEBUG: Exiting recursive_changed_detail')

    def is_dict(self,key):
        if isinstance(self.current_dict[key],dict):
            return 1
        else:
            return 0

    def calculate_changed_model(self):
        """
        Calculate the changed model.
        """
        result = dict()
        changed=self.changed()

        for s in changed:
            token=s
            self.recursive_changed_detail(s, token, s)
            self._add_results(all_changes)
            self._add_results(all_added)
            # TODO:  delete needs more work, not simply added to the results
            #self._add_results(all_removed)


    def _add_results(self, ar_changes):

        # The ar_changes is the keys of changes in the dotted format
        #  'resources.JDBCSystemResource.Generic2.JdbcResource.JDBCConnectionPoolParams.TestConnectionsOnReserve
        #
        #  Now change it to python dictionrary
        for item in ar_changes:
            debug('DEBUG: add_results %s', item)

            splitted=item.split('.',1)
            n=len(splitted)
            result=dict()
            walked=[]

            while n > 1:
                tmp=dict()
                tmp[splitted[0]]=dict()
                if len(result) > 0:
                    # traverse to the leaf
                    leaf=result
                    for k in walked:
                        leaf = leaf[k]
                    leaf[splitted[0]]=dict()
                    walked.append(splitted[0])
                else:
                    result=tmp
                    walked.append(splitted[0])
                splitted=splitted[1].split('.',1)
                n=len(splitted)
            #
            # result is the dictionary format
            #
            leaf=result
            value_tree=self.current_dict
            for k in walked:
                leaf = leaf[k]
                value_tree=value_tree[k]

            # walk the current dictionary and set the value
            # doesn't work in delete case
            #
            leaf[splitted[0]] = value_tree[splitted[0]]
            self.merge_dictionaries(self.final_changed_model, result)


    def merge_dictionaries(self, dictionary, new_dictionary):
        """
         Merge the values from the new dictionary to the existing one.
        :param dictionary: the existing dictionary
        :param new_dictionary: the new dictionary to be merged
        """
        for key in new_dictionary:
            new_value = new_dictionary[key]
            if key not in dictionary:
                dictionary[key] = new_value
            else:
                value = dictionary[key]
                if isinstance(value, dict) and isinstance(new_value, dict):
                    self.merge_dictionaries(value, new_value)
                else:
                    dictionary[key] = new_value

    def is_safe_diff(self, model):
        """
        Is it a safe difference to do online update.
        :param model: diffed model
        return 0 false ;
            1 true ;
            2 for fatal
            3 for no difference
        """

        # filter out any appDeployments for now. It is possible to support app but
        # case to handle include deletion, redeploy...
        #
        if model.has_key('appDeployments'):
            return UNSAFE_ONLINE_UPDATE

        # if nothing changed
        if not model or not bool(model):
            return MODELS_SAME

        # if there is anything not in existing model
        # WDT does not support certain type of deletion - entity level and no apps

        if len(all_removed) > 0:
            return UNSAFE_ONLINE_UPDATE

        if len(all_added) > 0:
            return self._is_safe_addition(model)


        return SAFE_ONLINE_UPDATE

    def _is_safe_addition(self, model):
        """
        check the items in all_added to see if can be used for online update
        return 1 tbd
               0 for safe online update
               2 fatal for any update
        """
        # allows add attribute to existing entity

        __safe = 0
        __fatal = 2

        found_in_past_dictionary = 1
        has_topology=0
        for itm in all_added:
            if itm.find('topology.') == 0:
                has_topology=1
            # print 'DEBUG: is_safe_addition ' + str(itm)
            found_in_past_dictionary = self._in_model(self.past_dict, itm)
            # print 'DBUEG: found_in_past_dictionary ' + str(found_in_past_dictionary)
            if not found_in_past_dictionary:
                break

        # if there is a shape change
        # return 2 ?
        if has_topology and not found_in_past_dictionary:
            print 'Found changes not supported for update: %s. Exiting' % (itm)
            return FATAL_MODEL_CHANGES

        if found_in_past_dictionary:
            return __safe

        # allow new additions for anything ??
        return __safe

    def _in_model(self, dictionary, keylist):
        """
        check whether the keys is in the dictionary
        :param dictionary dictonary to check
        :param keylist  dot separted key list

        return 1 if it is in model
               0 if it is not in model
        """
        debug('DBEUG: in model keylist=%s dictionary %s', keylist, dictionary)

        splitted=keylist.split('.')
        n=len(splitted)
        i=0
        root_key = splitted[0]

        # loop through the keys and use it to walk the dictionary
        # if it can walk down 3 levels, safely assume it is in the
        # dictionary, otherwise it is a total new addition

        for i in range(0, n):
            if dictionary.has_key(splitted[i]):
                if isinstance(dictionary[splitted[i]], dict):
                    dictionary = dictionary[splitted[i]]
                continue
            else:
                break

        if i > 2:
            return 1

        return 0

    def get_final_changed_model(self):
        """
        Return the changed model.
        """
        return self.final_changed_model



class ModelFileDiffer:

    def __init__(self, current_dict, past_dict):

        self.current_dict_file = current_dict
        self.past_dict_file = past_dict

    def eval_file(self, file):
        fh = open(file, 'r')
        content = fh.read()
        return eval(content)


    def write_dictionary_to_json_file(self, dictionary, writer, indent=''):
        """
        Write the python dictionary in json syntax using the provided writer stream.
        :param dictionary: python dictionary to convert to json syntax
        :param writer: where to write the dictionary into json syntax
        :param indent: current string indention of the json syntax. If not provided, indent is an empty string
        """
        _start_dict = '{'
        _end_dict = '}'

        if dictionary is None:
            return
        end_line = ''
        writer.write(_start_dict)
        end_indent = indent

        indent += ' '
        for key, value in dictionary.iteritems():
            writer.write(end_line)
            end_line = ','
            writer.write(indent + '"' + self.quote_embedded_quotes(key) + '" : ')
            if isinstance(value, dict):
                self.write_dictionary_to_json_file(value, writer, indent)
            else:
                writer.write(self.format_json_value(value))
        writer.write(str(end_indent + _end_dict))

        return

    def quote_embedded_quotes(self, text):
        """
        Quote all embedded double quotes in a string with a backslash.
        :param text: the text to quote
        :return: the quotes result
        """
        result = text
        if type(text) is str and '"' in text:
            result = text.replace('"', '\\"')
        return result

    def format_json_value(self, value):
        """
        Format the value as a JSON snippet.
        :param value: the value
        :return: the JSON snippet
        """
        import java.lang.StringBuilder as StringBuilder
        builder = StringBuilder()
        debug("DEBUG: value %s TYPE %s", value, type(value))
        if type(value) == bool or (type(value) == str and (value == 'true' or value == 'false')):
            if value:
                v = "true"
            else:
                v = "false"
            builder.append(v)
        elif type(value) == str:
            builder.append('"').append(self.quote_embedded_quotes(value)).append('"')
        elif type(value) == list:
            builder.append("[ ")
            ind = 0
            for list_item in value:
                if ind > 0:
                    builder.append(", ")
                builder.append('"').append(list_item).append('"')
                ind = ind+1

            builder.append(" ]")
        else:
            builder.append(value)
        return builder.toString()

    def compare(self):
        current_dict = self.eval_file(sys.argv[1])
        past_dict = self.eval_file(sys.argv[2])
        obj = ModelDiffer(current_dict, past_dict)
        obj.calculate_changed_model()
        net_diff = obj.get_final_changed_model()
        fh = open('/tmp/diffed_model.json', 'w')
        self.write_dictionary_to_json_file(net_diff, fh)
        fh.close()
        return obj.is_safe_diff(net_diff)

def debug(format_string, *arguments):
    if os.environ.has_key('DEBUG_INTROSPECT_JOB'):
        print format_string % (arguments)
    return

def main():
    obj = ModelFileDiffer(sys.argv[1], sys.argv[2])
    rc=obj.compare()
    exit(exitcode=rc)

if __name__ == "main":
    all_changes = []
    all_added = []
    all_removed = []
    main()


