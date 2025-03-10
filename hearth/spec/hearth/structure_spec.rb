# frozen_string_literal: true

module Hearth
  class MyUnion < Hearth::Union
    class StringValue < MyUnion
      def to_h
        { string_value: super(__getobj__) }
      end
    end
  end

  describe Structure do
    let(:struct) do
      Struct.new(
        :struct_value,
        :array_value,
        :hash_value,
        :value,
        :union_value,
        :some_object,
        keyword_init: true
      ) do
        include Hearth::Structure
      end
    end

    subject do
      struct.new(
        struct_value: struct.new(value: 'foo'),
        array_value: [
          struct.new(value: 'foo'),
          struct.new(value: 'bar')
        ],
        hash_value: { key: struct.new(value: 'value') },
        value: 'value',
        union_value: MyUnion::StringValue.new('union'),
        some_object: Object.new
      )
    end

    describe '#to_hash' do
      it 'serializes nested structs to a hash' do
        expected = {
          struct_value: { value: 'foo' },
          array_value: [
            { value: 'foo' },
            { value: 'bar' }
          ],
          hash_value: {
            key: { value: 'value' }
          },
          value: 'value',
          union_value: { string_value: 'union' },
          some_object: subject.some_object
        }
        expect(subject.to_h).to eq expected
      end
    end
  end
end
