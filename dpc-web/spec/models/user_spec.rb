# frozen_string_literal: true

RSpec.describe User, type: :model do
  subject { create :user }

  describe 'factory' do
    it { is_expected.to be_valid }
  end

  describe '#last_name' do
    it 'requires a last name' do
      subject.last_name = nil
      expect(subject).to_not be_valid
    end
  end

  describe '#first_name' do
    it 'requires a first name' do
      subject.first_name = nil
      expect(subject).to_not be_valid
    end
  end

  describe '#name' do
    it 'returns the full name' do
      expect(subject.name).to eq("#{subject.first_name} #{subject.last_name}")
    end
  end

  describe '#organization' do
    it 'is required' do
      subject.organization = nil
      expect(subject).to_not be_valid
    end
  end

  describe '#organization_type' do
    it 'is required' do
      subject.organization_type = nil
      expect(subject).to_not be_valid
    end

    it 'must be valid' do
      expect { subject.organization_type = 'blah-blah' }.to raise_error(ArgumentError)
    end
  end

  describe '#num_providers' do
    it 'defaults to 0' do
      expect(subject.num_providers).to be_zero
    end

    it 'must be greater than or equal to 0' do
      subject.num_providers = -1
      expect(subject).to_not be_valid
    end

    it 'must be an integer' do
      subject.num_providers = 1.23
      expect(subject).to_not be_valid
    end
  end

  describe '#address' do
    it 'first line is required' do
      subject.address_1 = nil
      expect(subject).to_not be_valid
    end
  end

  describe '#city' do
    it 'is required' do
      subject.city = nil
      expect(subject).to_not be_valid
    end
  end

  describe '#state' do
    it 'is required' do
      subject.city = nil
      expect(subject).to_not be_valid
    end

    it 'must be valid' do
      subject.state = 'FU'
      expect(subject).to_not be_valid
    end
  end

  describe '#zip' do
    it 'is required' do
      subject.zip = nil
      expect(subject).to_not be_valid
    end

    it 'accepts zip + 4' do
      subject.zip = '12345-6789'
      expect(subject).to be_valid
    end

    it 'must be valid' do
      subject.zip = '123456-756'
      expect(subject).to_not be_valid
    end
  end

  describe '#agree_to_terms' do
    it 'is required' do
      subject.agree_to_terms = nil
      expect(subject).to_not be_valid
    end

    it 'must be true to create user' do
      subject.agree_to_terms = false
      expect(subject).to_not be_valid
    end
  end
end
